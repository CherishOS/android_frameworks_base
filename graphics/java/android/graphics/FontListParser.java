/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.graphics;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.fonts.FontCustomizationParser;
import android.graphics.fonts.FontStyle;
import android.graphics.fonts.FontVariationAxis;
import android.os.Build;
import android.os.LocaleList;
import android.text.FontConfig;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parser for font config files.
 * @hide
 */
public class FontListParser {

    /* Parse fallback list (no names) */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static FontConfig parse(InputStream in) throws XmlPullParserException, IOException {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(in, null);
        parser.nextTag();
        return readFamilies(parser, "/system/fonts/", new FontCustomizationParser.Result(), null);
    }

    /**
     * Parses system font config XMLs
     *
     * @param fontsXmlPath location of fonts.xml
     * @param systemFontDir location of system font directory
     * @param oemCustomizationXmlPath location of oem_customization.xml
     * @param productFontDir location of oem customized font directory
     * @param updatableFontMap map of updated font files.
     * @return font configuration
     * @throws IOException
     * @throws XmlPullParserException
     */
    public static FontConfig parse(
            @NonNull String fontsXmlPath,
            @NonNull String systemFontDir,
            @Nullable String oemCustomizationXmlPath,
            @Nullable String productFontDir,
            @Nullable Map<String, File> updatableFontMap
    ) throws IOException, XmlPullParserException {
        FontCustomizationParser.Result oemCustomization;
        if (oemCustomizationXmlPath != null) {
            try (InputStream is = new FileInputStream(oemCustomizationXmlPath)) {
                oemCustomization = FontCustomizationParser.parse(is, productFontDir,
                        updatableFontMap);
            } catch (IOException e) {
                // OEM customization may not exists. Ignoring
                oemCustomization = new FontCustomizationParser.Result();
            }
        } else {
            oemCustomization = new FontCustomizationParser.Result();
        }

        try (InputStream is = new FileInputStream(fontsXmlPath)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(is, null);
            parser.nextTag();
            return readFamilies(parser, systemFontDir, oemCustomization, updatableFontMap);
        }
    }

    private static FontConfig readFamilies(
            @NonNull XmlPullParser parser,
            @NonNull String fontDir,
            @NonNull FontCustomizationParser.Result customization,
            @Nullable Map<String, File> updatableFontMap)
            throws XmlPullParserException, IOException {
        List<FontConfig.Family> families = new ArrayList<>();
        List<FontConfig.Alias> aliases = new ArrayList<>(customization.getAdditionalAliases());

        Map<String, FontConfig.Family> oemNamedFamilies =
                customization.getAdditionalNamedFamilies();

        parser.require(XmlPullParser.START_TAG, null, "familyset");
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if (tag.equals("family")) {
                FontConfig.Family family = readFamily(parser, fontDir, updatableFontMap);
                String name = family.getFallbackName();
                if (name == null || !oemNamedFamilies.containsKey(name)) {
                    // The OEM customization overrides system named family. Skip if OEM
                    // customization XML defines the same named family.
                    families.add(family);
                }
            } else if (tag.equals("alias")) {
                aliases.add(readAlias(parser));
            } else {
                skip(parser);
            }
        }

        families.addAll(oemNamedFamilies.values());
        return new FontConfig(families, aliases);
    }

    /**
     * Read family tag in fonts.xml or oem_customization.xml
     */
    public static FontConfig.Family readFamily(XmlPullParser parser, String fontDir,
            @Nullable Map<String, File> updatableFontMap)
            throws XmlPullParserException, IOException {
        final String name = parser.getAttributeValue(null, "name");
        final String lang = parser.getAttributeValue("", "lang");
        final String variant = parser.getAttributeValue(null, "variant");
        final List<FontConfig.Font> fonts = new ArrayList<>();
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            final String tag = parser.getName();
            if (tag.equals("font")) {
                fonts.add(readFont(parser, fontDir, updatableFontMap));
            } else {
                skip(parser);
            }
        }
        int intVariant = FontConfig.Family.VARIANT_DEFAULT;
        if (variant != null) {
            if (variant.equals("compact")) {
                intVariant = FontConfig.Family.VARIANT_COMPACT;
            } else if (variant.equals("elegant")) {
                intVariant = FontConfig.Family.VARIANT_ELEGANT;
            }
        }
        return new FontConfig.Family(fonts, name, LocaleList.forLanguageTags(lang), intVariant);
    }

    /** Matches leading and trailing XML whitespace. */
    private static final Pattern FILENAME_WHITESPACE_PATTERN =
            Pattern.compile("^[ \\n\\r\\t]+|[ \\n\\r\\t]+$");

    private static FontConfig.Font readFont(
            @NonNull XmlPullParser parser,
            @NonNull String fontDir,
            @Nullable Map<String, File> updatableFontMap)
            throws XmlPullParserException, IOException {

        String indexStr = parser.getAttributeValue(null, "index");
        int index = indexStr == null ? 0 : Integer.parseInt(indexStr);
        List<FontVariationAxis> axes = new ArrayList<>();
        String weightStr = parser.getAttributeValue(null, "weight");
        int weight = weightStr == null ? 400 : Integer.parseInt(weightStr);
        boolean isItalic = "italic".equals(parser.getAttributeValue(null, "style"));
        String fallbackFor = parser.getAttributeValue(null, "fallbackFor");
        StringBuilder filename = new StringBuilder();
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() == XmlPullParser.TEXT) {
                filename.append(parser.getText());
            }
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if (tag.equals("axis")) {
                axes.add(readAxis(parser));
            } else {
                skip(parser);
            }
        }
        String sanitizedName = FILENAME_WHITESPACE_PATTERN.matcher(filename).replaceAll("");
        String updatedName = findUpdatedFontFile(sanitizedName, updatableFontMap);
        String filePath;
        String originalPath;
        if (updatedName != null) {
            filePath = updatedName;
            originalPath = fontDir + sanitizedName;
        } else {
            filePath = fontDir + sanitizedName;
            originalPath = null;
        }

        String varSettings;
        if (axes.isEmpty()) {
            varSettings = "";
        } else {
            varSettings = FontVariationAxis.toFontVariationSettings(
                    axes.toArray(new FontVariationAxis[0]));
        }

        return new FontConfig.Font(new File(filePath),
                originalPath == null ? null : new File(originalPath),
                new FontStyle(
                        weight,
                        isItalic ? FontStyle.FONT_SLANT_ITALIC : FontStyle.FONT_SLANT_UPRIGHT
                ),
                index,
                varSettings,
                fallbackFor);
    }

    private static String findUpdatedFontFile(String name,
            @Nullable Map<String, File> updatableFontMap) {
        if (updatableFontMap != null) {
            File updatedFile = updatableFontMap.get(name);
            if (updatedFile != null) {
                return updatedFile.getAbsolutePath();
            }
        }
        return null;
    }

    private static FontVariationAxis readAxis(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        String tagStr = parser.getAttributeValue(null, "tag");
        String styleValueStr = parser.getAttributeValue(null, "stylevalue");
        skip(parser);  // axis tag is empty, ignore any contents and consume end tag
        return new FontVariationAxis(tagStr, Float.parseFloat(styleValueStr));
    }

    /**
     * Reads alias elements
     */
    public static FontConfig.Alias readAlias(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        String name = parser.getAttributeValue(null, "name");
        String toName = parser.getAttributeValue(null, "to");
        String weightStr = parser.getAttributeValue(null, "weight");
        int weight;
        if (weightStr == null) {
            weight = 400;
        } else {
            weight = Integer.parseInt(weightStr);
        }
        skip(parser);  // alias tag is empty, ignore any contents and consume end tag
        return new FontConfig.Alias(name, toName, weight);
    }

    /**
     * Skip until next element
     */
    public static void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            switch (parser.next()) {
                case XmlPullParser.START_TAG:
                    depth++;
                    break;
                case XmlPullParser.END_TAG:
                    depth--;
                    break;
            }
        }
    }
}
