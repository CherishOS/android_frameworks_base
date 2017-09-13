// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#define LOG_TAG "incidentd"

#include "FdBuffer.h"
#include "io_util.h"

#include <android-base/file.h>
#include <android-base/test_utils.h>
#include <fcntl.h>
#include <gtest/gtest.h>
#include <signal.h>
#include <string.h>

const int READ_TIMEOUT = 5 * 1000;
const int BUFFER_SIZE = 16 * 1024;
const int QUICK_TIMEOUT_MS = 100;
const std::string HEAD = "[OK]";

using namespace android;
using namespace android::base;
using ::testing::Test;

class FdBufferTest : public Test {
public:
    virtual void SetUp() override {
        ASSERT_NE(tf.fd, -1);
        ASSERT_NE(p2cPipe.init(), -1);
        ASSERT_NE(c2pPipe.init(), -1);
    }

    void AssertBufferReadSuccessful(size_t expected) {
        EXPECT_EQ(buffer.size(), expected);
        EXPECT_FALSE(buffer.timedOut());
        EXPECT_FALSE(buffer.truncated());
    }

    void AssertBufferContent(const char* expected) {
        int i=0;
        FdBuffer::iterator it = buffer.begin();
        while (expected[i] != '\0') {
            ASSERT_EQ(*it, expected[i++]);
            it++;
        }
        ASSERT_EQ(it, buffer.end());
    }

    bool DoDataStream(int rFd, int wFd) {
        char buf[BUFFER_SIZE];
        ssize_t nRead;
        while ((nRead = read(rFd, buf, BUFFER_SIZE)) > 0) {
            ssize_t nWritten = 0;
            while (nWritten < nRead) {
                ssize_t amt = write(wFd, buf + nWritten, nRead - nWritten);
                if (amt < 0) {
                    return false;
                }
                nWritten += amt;
            }
        }
        return nRead == 0;
    }

protected:
    FdBuffer buffer;
    TemporaryFile tf;
    Fpipe p2cPipe;
    Fpipe c2pPipe;

    const std::string kTestPath = GetExecutableDirectory();
    const std::string kTestDataPath = kTestPath + "/testdata/";
};

TEST_F(FdBufferTest, ReadAndWrite) {
    std::string testdata = "FdBuffer test string";
    ASSERT_TRUE(WriteStringToFile(testdata, tf.path, false));
    ASSERT_EQ(NO_ERROR, buffer.read(tf.fd, READ_TIMEOUT));
    AssertBufferReadSuccessful(testdata.size());
    AssertBufferContent(testdata.c_str());
}

TEST_F(FdBufferTest, IterateEmpty) {
    FdBuffer::iterator it = buffer.begin();
    EXPECT_EQ(it, buffer.end());
    it += 1;
    EXPECT_TRUE(it.outOfBound());
}

TEST_F(FdBufferTest, IteratorSnapshot) {
    FdBuffer::iterator it = buffer.begin();
    it += 4;
    FdBuffer::iterator snapshot = it.snapshot();
    it += 5;
    EXPECT_TRUE(snapshot != it);
    EXPECT_EQ(it - snapshot, 5);
    EXPECT_EQ(snapshot - it, -5);
}

TEST_F(FdBufferTest, ReadAndIterate) {
    std::string testdata = "FdBuffer test string";
    ASSERT_TRUE(WriteStringToFile(testdata, tf.path, false));
    ASSERT_EQ(NO_ERROR, buffer.read(tf.fd, READ_TIMEOUT));

    int i=0;
    for (FdBuffer::iterator it = buffer.begin(); it != buffer.end(); ++it) {
        EXPECT_EQ(*it, (uint8_t)testdata[i++]);
    }

    FdBuffer::iterator it = buffer.begin();
    it += buffer.size();
    EXPECT_EQ(it, buffer.end());
    EXPECT_EQ(it.bytesRead(), testdata.size());
    EXPECT_FALSE(it.outOfBound());
}

TEST_F(FdBufferTest, ReadTimeout) {
    int pid = fork();
    ASSERT_TRUE(pid != -1);

    if (pid == 0) {
        close(c2pPipe.readFd());
        while(true) {
            write(c2pPipe.writeFd(), "poo", 3);
            sleep(1);
        }
        _exit(EXIT_FAILURE);
    } else {
        close(c2pPipe.writeFd());

        status_t status = buffer.read(c2pPipe.readFd(), QUICK_TIMEOUT_MS);
        ASSERT_EQ(NO_ERROR, status);
        EXPECT_TRUE(buffer.timedOut());

        kill(pid, SIGKILL); // reap the child process
    }
}

TEST_F(FdBufferTest, ReadInStreamAndWrite) {
    std::string testdata = "simply test read in stream";
    std::string expected = HEAD + testdata;
    ASSERT_TRUE(WriteStringToFile(testdata, tf.path, false));

    int pid = fork();
    ASSERT_TRUE(pid != -1);

    if (pid == 0) {
        close(p2cPipe.writeFd());
        close(c2pPipe.readFd());
        ASSERT_TRUE(WriteStringToFd(HEAD, c2pPipe.writeFd()));
        ASSERT_TRUE(DoDataStream(p2cPipe.readFd(), c2pPipe.writeFd()));
        close(p2cPipe.readFd());
        close(c2pPipe.writeFd());
        // Must exit here otherwise the child process will continue executing the test binary.
        _exit(EXIT_SUCCESS);
    } else {
        close(p2cPipe.readFd());
        close(c2pPipe.writeFd());

        ASSERT_EQ(NO_ERROR, buffer.readProcessedDataInStream(tf.fd,
            p2cPipe.writeFd(), c2pPipe.readFd(), READ_TIMEOUT));
        AssertBufferReadSuccessful(HEAD.size() + testdata.size());
        AssertBufferContent(expected.c_str());
        wait(&pid);
    }
}

TEST_F(FdBufferTest, ReadInStreamAndWriteAllAtOnce) {
    std::string testdata = "child process flushes only after all data are read.";
    std::string expected = HEAD + testdata;
    ASSERT_TRUE(WriteStringToFile(testdata, tf.path, false));

    int pid = fork();
    ASSERT_TRUE(pid != -1);

    if (pid == 0) {
        close(p2cPipe.writeFd());
        close(c2pPipe.readFd());
        std::string data;
        // wait for read finishes then write.
        ASSERT_TRUE(ReadFdToString(p2cPipe.readFd(), &data));
        data = HEAD + data;
        ASSERT_TRUE(WriteStringToFd(data, c2pPipe.writeFd()));
        close(p2cPipe.readFd());
        close(c2pPipe.writeFd());
        // Must exit here otherwise the child process will continue executing the test binary.
        _exit(EXIT_SUCCESS);
    } else {
        close(p2cPipe.readFd());
        close(c2pPipe.writeFd());

        ASSERT_EQ(NO_ERROR, buffer.readProcessedDataInStream(tf.fd,
            p2cPipe.writeFd(), c2pPipe.readFd(), READ_TIMEOUT));
        AssertBufferReadSuccessful(HEAD.size() + testdata.size());
        AssertBufferContent(expected.c_str());
        wait(&pid);
    }
}

TEST_F(FdBufferTest, ReadInStreamEmpty) {
    ASSERT_TRUE(WriteStringToFile("", tf.path, false));

    int pid = fork();
    ASSERT_TRUE(pid != -1);

    if (pid == 0) {
        close(p2cPipe.writeFd());
        close(c2pPipe.readFd());
        ASSERT_TRUE(DoDataStream(p2cPipe.readFd(), c2pPipe.writeFd()));
        close(p2cPipe.readFd());
        close(c2pPipe.writeFd());
        _exit(EXIT_SUCCESS);
    } else {
        close(p2cPipe.readFd());
        close(c2pPipe.writeFd());

        ASSERT_EQ(NO_ERROR, buffer.readProcessedDataInStream(tf.fd,
            p2cPipe.writeFd(), c2pPipe.readFd(), READ_TIMEOUT));
        AssertBufferReadSuccessful(0);
        AssertBufferContent("");
        wait(&pid);
    }
}

TEST_F(FdBufferTest, ReadInStreamMoreThan4MB) {
    const std::string testFile = kTestDataPath + "morethan4MB.txt";
    size_t fourMB = (size_t) 4 * 1024 * 1024;
    int fd = open(testFile.c_str(), O_RDONLY | O_CLOEXEC);
    ASSERT_NE(fd, -1);
    int pid = fork();
    ASSERT_TRUE(pid != -1);

    if (pid == 0) {
        close(p2cPipe.writeFd());
        close(c2pPipe.readFd());
        ASSERT_TRUE(DoDataStream(p2cPipe.readFd(), c2pPipe.writeFd()));
        close(p2cPipe.readFd());
        close(c2pPipe.writeFd());
        _exit(EXIT_SUCCESS);
    } else {
        close(p2cPipe.readFd());
        close(c2pPipe.writeFd());

        ASSERT_EQ(NO_ERROR, buffer.readProcessedDataInStream(fd,
            p2cPipe.writeFd(), c2pPipe.readFd(), READ_TIMEOUT));
        EXPECT_EQ(buffer.size(), fourMB);
        EXPECT_FALSE(buffer.timedOut());
        EXPECT_TRUE(buffer.truncated());
        wait(&pid);
        FdBuffer::iterator it = buffer.begin();
        it += fourMB;
        EXPECT_EQ(it.bytesRead(), fourMB);
        EXPECT_EQ(it, buffer.end());
        for (FdBuffer::iterator it = buffer.begin(); it != buffer.end(); it++) {
            char c = 'A' + (it.bytesRead() % 64 / 8);
            ASSERT_TRUE(*it == c);
        }
    }
}

TEST_F(FdBufferTest, ReadInStreamTimeOut) {
    std::string testdata = "timeout test";
    ASSERT_TRUE(WriteStringToFile(testdata, tf.path, false));

    int pid = fork();
    ASSERT_TRUE(pid != -1);

    if (pid == 0) {
        close(p2cPipe.writeFd());
        close(c2pPipe.readFd());
        while (true) {
            sleep(1);
        }
        _exit(EXIT_FAILURE);
    } else {
        close(p2cPipe.readFd());
        close(c2pPipe.writeFd());

        ASSERT_EQ(NO_ERROR, buffer.readProcessedDataInStream(tf.fd,
            p2cPipe.writeFd(), c2pPipe.readFd(), QUICK_TIMEOUT_MS));
        EXPECT_TRUE(buffer.timedOut());
        kill(pid, SIGKILL); // reap the child process
    }
}
