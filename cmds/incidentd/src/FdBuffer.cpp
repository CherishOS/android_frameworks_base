/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "incidentd"

#include "FdBuffer.h"
#include "io_util.h"

#include <cutils/log.h>
#include <utils/SystemClock.h>

#include <fcntl.h>
#include <poll.h>
#include <unistd.h>
#include <wait.h>

const ssize_t BUFFER_SIZE = 16 * 1024; // 16 KB
const ssize_t MAX_BUFFER_COUNT = 256; // 4 MB max

FdBuffer::FdBuffer()
    :mBuffers(),
     mStartTime(-1),
     mFinishTime(-1),
     mCurrentWritten(-1),
     mTimedOut(false),
     mTruncated(false)
{
}

FdBuffer::~FdBuffer()
{
    const int N = mBuffers.size();
    for (int i=0; i<N; i++) {
        uint8_t* buf = mBuffers[i];
        free(buf);
    }
}

status_t
FdBuffer::read(int fd, int64_t timeout)
{
    struct pollfd pfds = {
        .fd = fd,
        .events = POLLIN
    };
    mStartTime = uptimeMillis();

    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK);

    uint8_t* buf = NULL;
    while (true) {
        if (mCurrentWritten >= BUFFER_SIZE || mCurrentWritten < 0) {
            if (mBuffers.size() == MAX_BUFFER_COUNT) {
                mTruncated = true;
                break;
            }
            buf = (uint8_t*)malloc(BUFFER_SIZE);
            if (buf == NULL) {
                return NO_MEMORY;
            }
            mBuffers.push_back(buf);
            mCurrentWritten = 0;
        }

        int64_t remainingTime = (mStartTime + timeout) - uptimeMillis();
        if (remainingTime <= 0) {
            mTimedOut = true;
            break;
        }

        int count = poll(&pfds, 1, remainingTime);
        if (count == 0) {
            mTimedOut = true;
            break;
        } else if (count < 0) {
            return -errno;
        } else {
            if ((pfds.revents & POLLERR) != 0) {
                return errno != 0 ? -errno : UNKNOWN_ERROR;
            } else {
                ssize_t amt = ::read(fd, buf + mCurrentWritten, BUFFER_SIZE - mCurrentWritten);
                if (amt < 0) {
                    if (errno == EAGAIN || errno == EWOULDBLOCK) {
                        continue;
                    } else {
                        return -errno;
                    }
                } else if (amt == 0) {
                    break;
                }
                mCurrentWritten += amt;
            }
        }
    }

    mFinishTime = uptimeMillis();
    return NO_ERROR;
}

status_t
FdBuffer::readProcessedDataInStream(int fd, int toFd, int fromFd, int64_t timeoutMs)
{
    struct pollfd pfds[] = {
        { .fd = fd,     .events = POLLIN  },
        { .fd = toFd,   .events = POLLOUT },
        { .fd = fromFd, .events = POLLIN  },
    };

    mStartTime = uptimeMillis();

    // mark all fds non blocking
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK);
    fcntl(toFd, F_SETFL, fcntl(toFd, F_GETFL, 0) | O_NONBLOCK);
    fcntl(fromFd, F_SETFL, fcntl(fromFd, F_GETFL, 0) | O_NONBLOCK);

    // A circular buffer holds data read from fd and writes to parsing process
    uint8_t cirBuf[BUFFER_SIZE];
    size_t cirSize = 0;
    int rpos = 0, wpos = 0;

    // This is the buffer used to store processed data
    uint8_t* buf = NULL;
    while (true) {
        if (mCurrentWritten >= BUFFER_SIZE || mCurrentWritten < 0) {
            if (mBuffers.size() == MAX_BUFFER_COUNT) {
                mTruncated = true;
                break;
            }
            buf = (uint8_t*)malloc(BUFFER_SIZE);
            if (buf == NULL) {
                return NO_MEMORY;
            }
            mBuffers.push_back(buf);
            mCurrentWritten = 0;
        }

        int64_t remainingTime = (mStartTime + timeoutMs) - uptimeMillis();
        if (remainingTime <= 0) {
            mTimedOut = true;
            break;
        }

        // wait for any pfds to be ready to perform IO
        int count = poll(pfds, 3, remainingTime);
        if (count == 0) {
            mTimedOut = true;
            break;
        } else if (count < 0) {
            return -errno;
        }

        // make sure no errors occur on any fds
        for (int i = 0; i < 3; ++i) {
            if ((pfds[i].revents & POLLERR) != 0) {
                return errno != 0 ? -errno : UNKNOWN_ERROR;
            }
        }

        // read from fd
        if (cirSize != BUFFER_SIZE && pfds[0].fd != -1) {
            ssize_t amt;
            if (rpos >= wpos) {
                amt = ::read(fd, cirBuf + rpos, BUFFER_SIZE - rpos);
            } else {
                amt = ::read(fd, cirBuf + rpos, wpos - rpos);
            }
            if (amt < 0) {
                if (!(errno == EAGAIN || errno == EWOULDBLOCK)) {
                    return -errno;
                } // otherwise just continue
            } else if (amt == 0) {  // reach EOF so don't have to poll pfds[0].
                ::close(pfds[0].fd);
                pfds[0].fd = -1;
            } else {
                rpos += amt;
                cirSize += amt;
            }
        }

        // write to parsing process
        if (cirSize > 0 && pfds[1].fd != -1) {
            ssize_t amt;
            if (rpos > wpos) {
                amt = ::write(toFd, cirBuf + wpos, rpos - wpos);
            } else {
                amt = ::write(toFd, cirBuf + wpos, BUFFER_SIZE - wpos);
            }
            if (amt < 0) {
                if (!(errno == EAGAIN || errno == EWOULDBLOCK)) {
                    return -errno;
                } // otherwise just continue
            } else {
                wpos += amt;
                cirSize -= amt;
            }
        }

        // if buffer is empty and fd is closed, close write fd.
        if (cirSize == 0 && pfds[0].fd == -1 && pfds[1].fd != -1) {
            ::close(pfds[1].fd);
            pfds[1].fd = -1;
        }

        // circular buffer, reset rpos and wpos
        if (rpos >= BUFFER_SIZE) {
            rpos = 0;
        }
        if (wpos >= BUFFER_SIZE) {
            wpos = 0;
        }

        // read from parsing process
        ssize_t amt = ::read(fromFd, buf + mCurrentWritten, BUFFER_SIZE - mCurrentWritten);
        if (amt < 0) {
            if (!(errno == EAGAIN || errno == EWOULDBLOCK)) {
                return -errno;
            } // otherwise just continue
        } else if (amt == 0) {
            break;
        } else {
            mCurrentWritten += amt;
        }
    }

    mFinishTime = uptimeMillis();
    return NO_ERROR;
}

size_t
FdBuffer::size() const
{
    if (mBuffers.empty()) return 0;
    return ((mBuffers.size() - 1) * BUFFER_SIZE) + mCurrentWritten;
}

status_t
FdBuffer::flush(int fd) const
{
    size_t i=0;
    status_t err = NO_ERROR;
    for (i=0; i<mBuffers.size()-1; i++) {
        err = write_all(fd, mBuffers[i], BUFFER_SIZE);
        if (err != NO_ERROR) return err;
    }
    return write_all(fd, mBuffers[i], mCurrentWritten);
}

FdBuffer::iterator
FdBuffer::begin() const
{
    return iterator(*this, 0, 0);
}

FdBuffer::iterator
FdBuffer::end() const
{
    if (mBuffers.empty() || mCurrentWritten < 0) return begin();
    if (mCurrentWritten == BUFFER_SIZE)
        // FdBuffer doesn't allocate another buf since no more bytes to read.
        return FdBuffer::iterator(*this, mBuffers.size(), 0);
    return FdBuffer::iterator(*this, mBuffers.size() - 1, mCurrentWritten);
}

// ===============================================================================
FdBuffer::iterator::iterator(const FdBuffer& buffer, ssize_t index, ssize_t offset)
        : mFdBuffer(buffer),
          mIndex(index),
          mOffset(offset)
{
}

FdBuffer::iterator&
FdBuffer::iterator::operator=(iterator& other) const { return other; }

FdBuffer::iterator&
FdBuffer::iterator::operator+(size_t offset)
{
    size_t newOffset = mOffset + offset;
    while (newOffset >= BUFFER_SIZE) {
        mIndex++;
        newOffset -= BUFFER_SIZE;
    }
    mOffset = newOffset;
    return *this;
}

FdBuffer::iterator&
FdBuffer::iterator::operator+=(size_t offset) { return *this + offset; }

FdBuffer::iterator&
FdBuffer::iterator::operator++() { return *this + 1; }

FdBuffer::iterator
FdBuffer::iterator::operator++(int) { return *this + 1; }

bool
FdBuffer::iterator::operator==(iterator other) const
{
    return mIndex == other.mIndex && mOffset == other.mOffset;
}

bool
FdBuffer::iterator::operator!=(iterator other) const { return !(*this == other); }

int
FdBuffer::iterator::operator-(iterator other) const
{
    return (int)bytesRead() - (int)other.bytesRead();
}

FdBuffer::iterator::reference
FdBuffer::iterator::operator*() const
{
    return mFdBuffer.mBuffers[mIndex][mOffset];
}

FdBuffer::iterator
FdBuffer::iterator::snapshot() const
{
    return FdBuffer::iterator(mFdBuffer, mIndex, mOffset);
}

size_t
FdBuffer::iterator::bytesRead() const
{
    return mIndex * BUFFER_SIZE + mOffset;
}

bool
FdBuffer::iterator::outOfBound() const
{
    return bytesRead() > mFdBuffer.size();
}
