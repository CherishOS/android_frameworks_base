/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.text;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.util.IntArray;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;

/**
 * A text which has already been measured.
 *
 * TODO: Rename to better name? e.g. MeasuredText, FrozenText etc.
 */
public class PremeasuredText implements Spanned {
    private static final char LINE_FEED = '\n';

    // The original text.
    private final @NonNull CharSequence mText;

    // The inclusive start offset of the measuring target.
    private final @IntRange(from = 0) int mStart;

    // The exclusive end offset of the measuring target.
    private final @IntRange(from = 0) int mEnd;

    // The TextPaint used for measurement.
    private final @NonNull TextPaint mPaint;

    // The requested text direction.
    private final @NonNull TextDirectionHeuristic mTextDir;

    // The measured paragraph texts.
    private final @NonNull MeasuredText[] mMeasuredTexts;

    // The sorted paragraph end offsets.
    private final @NonNull int[] mParagraphBreakPoints;

    /**
     * Build PremeasuredText from the text.
     *
     * @param text The text to be measured.
     * @param paint The paint to be used for drawing.
     * @param textDir The text direction.
     * @return The measured text.
     */
    public static @NonNull PremeasuredText build(@NonNull CharSequence text,
                                                 @NonNull TextPaint paint,
                                                 @NonNull TextDirectionHeuristic textDir) {
        return PremeasuredText.build(text, paint, textDir, 0, text.length());
    }

    /**
     * Build PremeasuredText from the specific range of the text..
     *
     * @param text The text to be measured.
     * @param paint The paint to be used for drawing.
     * @param textDir The text direction.
     * @param start The inclusive start offset of the text.
     * @param end The exclusive start offset of the text.
     * @return The measured text.
     */
    public static @NonNull PremeasuredText build(@NonNull CharSequence text,
                                                 @NonNull TextPaint paint,
                                                 @NonNull TextDirectionHeuristic textDir,
                                                 @IntRange(from = 0) int start,
                                                 @IntRange(from = 0) int end) {
        Preconditions.checkNotNull(text);
        Preconditions.checkNotNull(paint);
        Preconditions.checkNotNull(textDir);
        Preconditions.checkArgumentInRange(start, 0, text.length(), "start");
        Preconditions.checkArgumentInRange(end, 0, text.length(), "end");

        final IntArray paragraphEnds = new IntArray();
        final ArrayList<MeasuredText> measuredTexts = new ArrayList<>();

        int paraEnd = 0;
        for (int paraStart = start; paraStart < end; paraStart = paraEnd) {
            paraEnd = TextUtils.indexOf(text, LINE_FEED, paraStart, end);
            if (paraEnd < 0) {
                // No LINE_FEED(U+000A) character found. Use end of the text as the paragraph end.
                paraEnd = end;
            } else {
                paraEnd++;  // Includes LINE_FEED(U+000A) to the prev paragraph.
            }

            paragraphEnds.add(paraEnd);
            measuredTexts.add(MeasuredText.buildForStaticLayout(
                    paint, text, paraStart, paraEnd, textDir, null /* no recycle */));
        }

        return new PremeasuredText(text, start, end, paint, textDir,
                                   measuredTexts.toArray(new MeasuredText[measuredTexts.size()]),
                                   paragraphEnds.toArray());
    }

    // Use PremeasuredText.build instead.
    private PremeasuredText(@NonNull CharSequence text,
                            @IntRange(from = 0) int start,
                            @IntRange(from = 0) int end,
                            @NonNull TextPaint paint,
                            @NonNull TextDirectionHeuristic textDir,
                            @NonNull MeasuredText[] measuredTexts,
                            @NonNull int[] paragraphBreakPoints) {
        mText = text;
        mStart = start;
        mEnd = end;
        mPaint = paint;
        mMeasuredTexts = measuredTexts;
        mParagraphBreakPoints = paragraphBreakPoints;
        mTextDir = textDir;
    }

    /**
     * Return the underlying text.
     */
    public @NonNull CharSequence getText() {
        return mText;
    }

    /**
     * Returns the inclusive start offset of measured region.
     */
    public @IntRange(from = 0) int getStart() {
        return mStart;
    }

    /**
     * Returns the exclusive end offset of measured region.
     */
    public @IntRange(from = 0) int getEnd() {
        return mEnd;
    }

    /**
     * Returns the text direction associated with char sequence.
     */
    public @NonNull TextDirectionHeuristic getTextDir() {
        return mTextDir;
    }

    /**
     * Returns the paint used to measure this text.
     */
    public @NonNull TextPaint getPaint() {
        return mPaint;
    }

    /**
     * Returns the length of the paragraph of this text.
     */
    public @IntRange(from = 0) int getParagraphCount() {
        return mParagraphBreakPoints.length;
    }

    /**
     * Returns the paragraph start offset of the text.
     */
    public @IntRange(from = 0) int getParagraphStart(@IntRange(from = 0) int paraIndex) {
        Preconditions.checkArgumentInRange(paraIndex, 0, getParagraphCount(), "paraIndex");
        return paraIndex == 0 ? mStart : mParagraphBreakPoints[paraIndex - 1];
    }

    /**
     * Returns the paragraph end offset of the text.
     */
    public @IntRange(from = 0) int getParagraphEnd(@IntRange(from = 0) int paraIndex) {
        Preconditions.checkArgumentInRange(paraIndex, 0, getParagraphCount(), "paraIndex");
        return mParagraphBreakPoints[paraIndex];
    }

    /** @hide */
    public @NonNull MeasuredText getMeasuredText(@IntRange(from = 0) int paraIndex) {
        return mMeasuredTexts[paraIndex];
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Spanned overrides
    //
    // Just proxy for underlying mText if appropriate.

    @Override
    public <T> T[] getSpans(int start, int end, Class<T> type) {
        if (mText instanceof Spanned) {
            return ((Spanned) mText).getSpans(start, end, type);
        } else {
            return ArrayUtils.emptyArray(type);
        }
    }

    @Override
    public int getSpanStart(Object tag) {
        if (mText instanceof Spanned) {
            return ((Spanned) mText).getSpanStart(tag);
        } else {
            return -1;
        }
    }

    @Override
    public int getSpanEnd(Object tag) {
        if (mText instanceof Spanned) {
            return ((Spanned) mText).getSpanEnd(tag);
        } else {
            return -1;
        }
    }

    @Override
    public int getSpanFlags(Object tag) {
        if (mText instanceof Spanned) {
            return ((Spanned) mText).getSpanFlags(tag);
        } else {
            return 0;
        }
    }

    @Override
    public int nextSpanTransition(int start, int limit, Class type) {
        if (mText instanceof Spanned) {
            return ((Spanned) mText).nextSpanTransition(start, limit, type);
        } else {
            return mText.length();
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // CharSequence overrides.
    //
    // Just proxy for underlying mText.

    @Override
    public int length() {
        return mText.length();
    }

    @Override
    public char charAt(int index) {
        // TODO: Should this be index + mStart ?
        return mText.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        // TODO: return PremeasuredText.
        // TODO: Should this be index + mStart, end + mStart ?
        return mText.subSequence(start, end);
    }

    @Override
    public String toString() {
        return mText.toString();
    }
}
