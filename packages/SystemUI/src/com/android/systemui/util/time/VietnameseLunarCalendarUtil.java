/*
 * Copyright (C) 2021 The exTHmUI Open Source Project
 * Copyright (C) 2021 The CherishOS Open Source Project
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

package com.android.systemui.util.time;

import android.icu.util.ChineseCalendar;

public class VietnameseLunarCalendarUtil {

    private static final String[] YEAR_TIANGAN = new String[]{"Giáp", "Ất", "Bính", "Đinh", "Mậu", "Kỷ", "Canh", "Tân", "Nhâm", "Quý"};
    private static final String[] YEAR_DIZHI = new String[]{"Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi", "Thân", "Dậu", "Tuất", "Hợi"};
    private static final String[] DIGITS = new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"};

    public static final int FLAG_INCLUDE_DATE = 1;
    public static final int FLAG_INCLUDE_MONTH = 1 << 1;
    public static final int FLAG_INCLUDE_YEAR = 1 << 2;
    public static final int FLAG_INCLUDE_CHINESE_ZODIAC = 1 << 3;

    public static String getLunarDateString() {
        return getLunarDateString(FLAG_INCLUDE_MONTH | FLAG_INCLUDE_DATE);
    }

    public static String getLunarDateString(int flag) {
        return getLunarDateString(new ChineseCalendar(), flag);
    }

    public static String getLunarDateString(ChineseCalendar chineseCalendar, int flag) {
        StringBuilder sb = new StringBuilder();
        if ((flag & FLAG_INCLUDE_YEAR) == FLAG_INCLUDE_YEAR) {
            int year = chineseCalendar.get(ChineseCalendar.YEAR);
            boolean zodiac = (flag & FLAG_INCLUDE_CHINESE_ZODIAC) == FLAG_INCLUDE_CHINESE_ZODIAC;
            sb.append(convYear(year, zodiac));
            sb.append(" ");
        }
        if ((flag & FLAG_INCLUDE_MONTH) == FLAG_INCLUDE_MONTH) {
            int month = chineseCalendar.get(ChineseCalendar.MONTH) + 1;
            boolean isLeapMonth = chineseCalendar.get(ChineseCalendar.IS_LEAP_MONTH) == 1;
            sb.append(convMonth(month, isLeapMonth));
        }
        if ((flag & FLAG_INCLUDE_DATE) == FLAG_INCLUDE_DATE) {
            int date = chineseCalendar.get(ChineseCalendar.DATE);
            sb.append(convDate(date));
        }
        return sb.toString();
    }

    private static String convYear(int year, boolean zodiac) {
        int y = year - 1;
        int tiangan = y % 10;
        int dizhi = y % 12;
        return "Năm" + YEAR_TIANGAN[tiangan] + YEAR_DIZHI[dizhi];
    }

    private static String convMonth(int month, boolean isLeapMonth) {
        if (isLeapMonth) {
            return "Thg" + "Nhuận" + DIGITS[month - 1];
        } else {
            return "Thg" + DIGITS[month - 1];
        }
    }

    private static String convDate(int d) {
        if (d <= 10) {
            return "Mùng " + DIGITS[d - 1];
        } else if (d < 20) {
            return "1" + DIGITS[d % 10 - 1];
        } else if (d == 20) {
            return  "20";
        } else if (d < 30) {
            return "2" + DIGITS[d % 10 - 1];
        } else if (d == 30) {
            return  "30";
        }
        return "";
    }
}
