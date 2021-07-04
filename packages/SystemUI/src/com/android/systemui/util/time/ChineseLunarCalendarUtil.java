/*
 * Copyright (C) 2021 The exTHmUI Open Source Project
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

public class ChineseLunarCalendarUtil {

    private static final String[] YEAR_TIANGAN = new String[]{"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"};
    private static final String[] YEAR_DIZHI = new String[]{"子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"};
    private static final String[] YEAR_CHINESE_ZODIAC = new String[]{"鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪"};
    private static final String[] DIGITS = new String[]{"一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二"};

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
        return YEAR_TIANGAN[tiangan] + YEAR_DIZHI[dizhi] + (zodiac ? YEAR_CHINESE_ZODIAC[dizhi] : "") + "年";
    }

    private static String convMonth(int month, boolean isLeapMonth) {
        if (isLeapMonth) {
            return "闰" + DIGITS[month - 1] + "月";
        } else {
            return DIGITS[month - 1] + "月";
        }
    }

    private static String convDate(int d) {
        if (d <= 10) {
            return "初" + DIGITS[d - 1];
        } else if (d < 20) {
            return "十" + DIGITS[d % 10 - 1];
        } else if (d == 20) {
            return  "二十";
        } else if (d < 30) {
            return "廿" + DIGITS[d % 10 - 1];
        } else if (d == 30) {
            return  "三十";
        }
        return "";
    }
}
