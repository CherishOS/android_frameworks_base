/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations;

import com.android.internal.widget.remotecompose.core.CompanionOperation;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.WireBuffer;

import java.util.List;

/**
 * Draw Text
 */
public class DrawText extends PaintOperation {
    public static final Companion COMPANION = new Companion();
    int mTextID;
    int mStart = 0;
    int mEnd = 0;
    int mContextStart = 0;
    int mContextEnd = 0;
    float mX = 0f;
    float mY = 0f;
    boolean mRtl = false;

    public DrawText(int textID,
                    int start,
                    int end,
                    int contextStart,
                    int contextEnd,
                    float x,
                    float y,
                    boolean rtl) {
        mTextID = textID;
        mStart = start;
        mEnd = end;
        mContextStart = contextStart;
        mContextEnd = contextEnd;
        mX = x;
        mY = y;
        mRtl = rtl;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mTextID, mStart, mEnd, mContextStart, mContextEnd, mX, mY, mRtl);

    }

    @Override
    public String toString() {
        return "DrawTextRun [" + mTextID + "] " + mStart + ", " + mEnd + ", " + mX + ", " + mY;
    }

    public static class Companion implements CompanionOperation {
        private Companion() {
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int text = buffer.readInt();
            int start = buffer.readInt();
            int end = buffer.readInt();
            int contextStart = buffer.readInt();
            int contextEnd = buffer.readInt();
            float x = buffer.readFloat();
            float y = buffer.readFloat();
            boolean rtl = buffer.readBoolean();
            DrawText op = new DrawText(text, start, end, contextStart, contextEnd, x, y, rtl);

            operations.add(op);
        }

        @Override
        public String name() {
            return "";
        }

        @Override
        public int id() {
            return 0;
        }

        /**
         * Writes out the operation to the buffer
         * @param buffer
         * @param textID
         * @param start
         * @param end
         * @param contextStart
         * @param contextEnd
         * @param x
         * @param y
         * @param rtl
         */
        public void apply(WireBuffer buffer,
                          int textID,
                          int start,
                          int end,
                          int contextStart,
                          int contextEnd,
                          float x,
                          float y,
                          boolean rtl) {
            buffer.start(Operations.DRAW_TEXT_RUN);
            buffer.writeInt(textID);
            buffer.writeInt(start);
            buffer.writeInt(end);
            buffer.writeInt(contextStart);
            buffer.writeInt(contextEnd);
            buffer.writeFloat(x);
            buffer.writeFloat(y);
            buffer.writeBoolean(rtl);
        }
    }

    @Override
    public void paint(PaintContext context) {
        context.drawTextRun(mTextID, mStart, mEnd, mContextStart, mContextEnd, mX, mY, mRtl);
    }
}
