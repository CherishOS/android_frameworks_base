/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.hardware.radio.tests.unittests;

import static com.google.common.truth.Truth.assertWithMessage;

import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioMetadata;

import org.junit.Test;

import java.util.Arrays;

public final class RadioManagerTest {

    private static final int REGION = RadioManager.REGION_ITU_2;
    private static final int FM_LOWER_LIMIT = 87500;
    private static final int FM_UPPER_LIMIT = 108000;
    private static final int FM_SPACING = 200;
    private static final int AM_LOWER_LIMIT = 540;
    private static final int AM_UPPER_LIMIT = 1700;
    private static final int AM_SPACING = 10;
    private static final boolean STEREO_SUPPORTED = true;
    private static final boolean RDS_SUPPORTED = true;
    private static final boolean TA_SUPPORTED = false;
    private static final boolean AF_SUPPORTED = false;
    private static final boolean EA_SUPPORTED = false;

    private static final int PROPERTIES_ID = 10;
    private static final String SERVICE_NAME = "ServiceNameMock";
    private static final int CLASS_ID = RadioManager.CLASS_AM_FM;
    private static final String IMPLEMENTOR = "ImplementorMock";
    private static final String PRODUCT = "ProductMock";
    private static final String VERSION = "VersionMock";
    private static final String SERIAL = "SerialMock";
    private static final int NUM_TUNERS = 1;
    private static final int NUM_AUDIO_SOURCES = 1;
    private static final boolean IS_INITIALIZATION_REQUIRED = false;
    private static final boolean IS_CAPTURE_SUPPORTED = false;
    private static final boolean IS_BG_SCAN_SUPPORTED = true;
    private static final int[] SUPPORTED_PROGRAM_TYPES = new int[]{
            ProgramSelector.PROGRAM_TYPE_AM, ProgramSelector.PROGRAM_TYPE_FM};
    private static final int[] SUPPORTED_IDENTIFIERS_TYPES = new int[]{
            ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY, ProgramSelector.IDENTIFIER_TYPE_RDS_PI};

    private static final RadioManager.FmBandDescriptor FM_BAND_DESCRIPTOR =
            createFmBandDescriptor();
    private static final RadioManager.AmBandDescriptor AM_BAND_DESCRIPTOR =
            createAmBandDescriptor();
    private static final RadioManager.FmBandConfig FM_BAND_CONFIG = createFmBandConfig();
    private static final RadioManager.AmBandConfig AM_BAND_CONFIG = createAmBandConfig();
    private static final RadioManager.ModuleProperties AMFM_PROPERTIES = createAmFmProperties();

    /**
     * Info flags with live, tuned and stereo enabled
     */
    private static final int INFO_FLAGS = 0b110001;
    private static final int SIGNAL_QUALITY = 2;
    private static final ProgramSelector.Identifier DAB_SID_EXT_IDENTIFIER =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_SID_EXT,
                    /* value= */ 0x10000111);
    private static final ProgramSelector.Identifier DAB_SID_EXT_IDENTIFIER_RELATED =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_SID_EXT,
                    /* value= */ 0x10000113);
    private static final ProgramSelector.Identifier DAB_ENSEMBLE_IDENTIFIER =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_ENSEMBLE,
                    /* value= */ 0x1013);
    private static final ProgramSelector.Identifier DAB_FREQUENCY_IDENTIFIER =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY,
                    /* value= */ 95500);
    private static final ProgramSelector DAB_SELECTOR =
            new ProgramSelector(ProgramSelector.PROGRAM_TYPE_DAB, DAB_SID_EXT_IDENTIFIER,
                    new ProgramSelector.Identifier[]{
                            DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER},
                    /* vendorIds= */ null);
    private static final RadioMetadata METADATA = createMetadata();
    private static final RadioManager.ProgramInfo DAB_PROGRAM_INFO =
            createDabProgramInfo(DAB_SELECTOR);

    @Test
    public void getType_forBandDescriptor() {
        RadioManager.BandDescriptor bandDescriptor = createAmBandDescriptor();

        assertWithMessage("AM Band Descriptor type")
                .that(bandDescriptor.getType()).isEqualTo(RadioManager.BAND_AM);
    }

    @Test
    public void getRegion_forBandDescriptor() {
        RadioManager.BandDescriptor bandDescriptor = createFmBandDescriptor();

        assertWithMessage("FM Band Descriptor region")
                .that(bandDescriptor.getRegion()).isEqualTo(REGION);
    }

    @Test
    public void getLowerLimit_forBandDescriptor() {
        RadioManager.BandDescriptor bandDescriptor = createFmBandDescriptor();

        assertWithMessage("FM Band Descriptor lower limit")
                .that(bandDescriptor.getLowerLimit()).isEqualTo(FM_LOWER_LIMIT);
    }

    @Test
    public void getUpperLimit_forBandDescriptor() {
        RadioManager.BandDescriptor bandDescriptor = createAmBandDescriptor();

        assertWithMessage("AM Band Descriptor upper limit")
                .that(bandDescriptor.getUpperLimit()).isEqualTo(AM_UPPER_LIMIT);
    }

    @Test
    public void getSpacing_forBandDescriptor() {
        RadioManager.BandDescriptor bandDescriptor = createAmBandDescriptor();

        assertWithMessage("AM Band Descriptor spacing")
                .that(bandDescriptor.getSpacing()).isEqualTo(AM_SPACING);
    }

    @Test
    public void isAmBand_forAmBandDescriptor_returnsTrue() {
        RadioManager.BandDescriptor bandDescriptor = createAmBandDescriptor();

        assertWithMessage("Is AM Band Descriptor an AM band")
                .that(bandDescriptor.isAmBand()).isTrue();
    }

    @Test
    public void isFmBand_forAmBandDescriptor_returnsFalse() {
        RadioManager.BandDescriptor bandDescriptor = createAmBandDescriptor();

        assertWithMessage("Is AM Band Descriptor an FM band")
                .that(bandDescriptor.isFmBand()).isFalse();
    }

    @Test
    public void isStereoSupported_forFmBandDescriptor() {
        assertWithMessage("FM Band Descriptor stereo")
                .that(FM_BAND_DESCRIPTOR.isStereoSupported()).isEqualTo(STEREO_SUPPORTED);
    }

    @Test
    public void isRdsSupported_forFmBandDescriptor() {
        assertWithMessage("FM Band Descriptor RDS or RBDS")
                .that(FM_BAND_DESCRIPTOR.isRdsSupported()).isEqualTo(RDS_SUPPORTED);
    }

    @Test
    public void isTaSupported_forFmBandDescriptor() {
        assertWithMessage("FM Band Descriptor traffic announcement")
                .that(FM_BAND_DESCRIPTOR.isTaSupported()).isEqualTo(TA_SUPPORTED);
    }

    @Test
    public void isAfSupported_forFmBandDescriptor() {
        assertWithMessage("FM Band Descriptor alternate frequency")
                .that(FM_BAND_DESCRIPTOR.isAfSupported()).isEqualTo(AF_SUPPORTED);
    }

    @Test
    public void isEaSupported_forFmBandDescriptor() {
        assertWithMessage("FM Band Descriptor emergency announcement")
                .that(FM_BAND_DESCRIPTOR.isEaSupported()).isEqualTo(EA_SUPPORTED);
    }

    @Test
    public void isStereoSupported_forAmBandDescriptor() {
        assertWithMessage("AM Band Descriptor stereo")
                .that(AM_BAND_DESCRIPTOR.isStereoSupported()).isEqualTo(STEREO_SUPPORTED);
    }

    @Test
    public void equals_withSameFmBandDescriptors_returnsTrue() {
        RadioManager.FmBandDescriptor fmBandDescriptor1 = createFmBandDescriptor();
        RadioManager.FmBandDescriptor fmBandDescriptor2 = createFmBandDescriptor();

        assertWithMessage("The same FM Band Descriptor")
                .that(fmBandDescriptor1).isEqualTo(fmBandDescriptor2);
    }

    @Test
    public void equals_withSameAmBandDescriptors_returnsTrue() {
        RadioManager.AmBandDescriptor amBandDescriptorCompared = createAmBandDescriptor();

        assertWithMessage("The same AM Band Descriptor")
                .that(AM_BAND_DESCRIPTOR).isEqualTo(amBandDescriptorCompared);
    }

    @Test
    public void equals_withAmBandDescriptorsOfDifferentUpperLimits_returnsFalse() {
        RadioManager.AmBandDescriptor amBandDescriptorCompared =
                new RadioManager.AmBandDescriptor(REGION, RadioManager.BAND_AM, AM_LOWER_LIMIT,
                        AM_UPPER_LIMIT + AM_SPACING, AM_SPACING, STEREO_SUPPORTED);

        assertWithMessage("AM Band Descriptor of different upper limit")
                .that(AM_BAND_DESCRIPTOR).isNotEqualTo(amBandDescriptorCompared);
    }

    @Test
    public void equals_withAmAndFmBandDescriptors_returnsFalse() {
        assertWithMessage("AM Band Descriptor")
                .that(AM_BAND_DESCRIPTOR).isNotEqualTo(FM_BAND_DESCRIPTOR);
    }

    @Test
    public void getType_forBandConfig() {
        RadioManager.BandConfig fmBandConfig = createFmBandConfig();

        assertWithMessage("FM Band Config type")
                .that(fmBandConfig.getType()).isEqualTo(RadioManager.BAND_FM);
    }

    @Test
    public void getRegion_forBandConfig() {
        RadioManager.BandConfig amBandConfig = createAmBandConfig();

        assertWithMessage("AM Band Config region")
                .that(amBandConfig.getRegion()).isEqualTo(REGION);
    }

    @Test
    public void getLowerLimit_forBandConfig() {
        RadioManager.BandConfig amBandConfig = createAmBandConfig();

        assertWithMessage("AM Band Config lower limit")
                .that(amBandConfig.getLowerLimit()).isEqualTo(AM_LOWER_LIMIT);
    }

    @Test
    public void getUpperLimit_forBandConfig() {
        RadioManager.BandConfig fmBandConfig = createFmBandConfig();

        assertWithMessage("FM Band Config upper limit")
                .that(fmBandConfig.getUpperLimit()).isEqualTo(FM_UPPER_LIMIT);
    }

    @Test
    public void getSpacing_forBandConfig() {
        RadioManager.BandConfig fmBandConfig = createFmBandConfig();

        assertWithMessage("FM Band Config spacing")
                .that(fmBandConfig.getSpacing()).isEqualTo(FM_SPACING);
    }

    @Test
    public void getStereo_forFmBandConfig() {
        assertWithMessage("FM Band Config stereo ")
                .that(FM_BAND_CONFIG.getStereo()).isEqualTo(STEREO_SUPPORTED);
    }

    @Test
    public void getRds_forFmBandConfig() {
        assertWithMessage("FM Band Config RDS or RBDS")
                .that(FM_BAND_CONFIG.getRds()).isEqualTo(RDS_SUPPORTED);
    }

    @Test
    public void getTa_forFmBandConfig() {
        assertWithMessage("FM Band Config traffic announcement")
                .that(FM_BAND_CONFIG.getTa()).isEqualTo(TA_SUPPORTED);
    }

    @Test
    public void getAf_forFmBandConfig() {
        assertWithMessage("FM Band Config alternate frequency")
                .that(FM_BAND_CONFIG.getAf()).isEqualTo(AF_SUPPORTED);
    }

    @Test
    public void getEa_forFmBandConfig() {
        assertWithMessage("FM Band Config emergency Announcement")
                .that(FM_BAND_CONFIG.getEa()).isEqualTo(EA_SUPPORTED);
    }

    @Test
    public void getStereo_forAmBandConfig() {
        assertWithMessage("AM Band Config stereo")
                .that(AM_BAND_CONFIG.getStereo()).isEqualTo(STEREO_SUPPORTED);
    }

    @Test
    public void equals_withSameFmBandConfigs_returnsTrue() {
        RadioManager.FmBandConfig fmBandConfigCompared = createFmBandConfig();

        assertWithMessage("The same FM Band Config")
                .that(FM_BAND_CONFIG).isEqualTo(fmBandConfigCompared);
    }

    @Test
    public void equals_withFmBandConfigsOfDifferentAfs_returnsFalse() {
        RadioManager.FmBandConfig.Builder builder = new RadioManager.FmBandConfig.Builder(
                createFmBandDescriptor()).setStereo(STEREO_SUPPORTED).setRds(RDS_SUPPORTED)
                .setTa(TA_SUPPORTED).setAf(!AF_SUPPORTED).setEa(EA_SUPPORTED);
        RadioManager.FmBandConfig fmBandConfigFromBuilder = builder.build();

        assertWithMessage("FM Band Config of different af value")
                .that(FM_BAND_CONFIG).isNotEqualTo(fmBandConfigFromBuilder);
    }

    @Test
    public void equals_withFmAndAmBandConfigs_returnsFalse() {
        assertWithMessage("FM Band Config")
                .that(FM_BAND_CONFIG).isNotEqualTo(AM_BAND_CONFIG);
    }

    @Test
    public void equals_withSameAmBandConfigs_returnsTrue() {
        RadioManager.AmBandConfig amBandConfigCompared = createAmBandConfig();

        assertWithMessage("The same AM Band Config")
                .that(AM_BAND_CONFIG).isEqualTo(amBandConfigCompared);
    }

    @Test
    public void equals_withAmBandConfigsOfDifferentTypes_returnsFalse() {
        RadioManager.AmBandConfig amBandConfigCompared = new RadioManager.AmBandConfig(
                new RadioManager.AmBandDescriptor(REGION, RadioManager.BAND_AM_HD, AM_LOWER_LIMIT,
                        AM_UPPER_LIMIT, AM_SPACING, STEREO_SUPPORTED));

        assertWithMessage("AM Band Config of different type")
                .that(AM_BAND_CONFIG).isNotEqualTo(amBandConfigCompared);
    }

    @Test
    public void equals_withAmBandConfigsOfDifferentStereoValues_returnsFalse() {
        RadioManager.AmBandConfig.Builder builder = new RadioManager.AmBandConfig.Builder(
                createAmBandDescriptor()).setStereo(!STEREO_SUPPORTED);
        RadioManager.AmBandConfig amBandConfigFromBuilder = builder.build();

        assertWithMessage("AM Band Config of different stereo value")
                .that(AM_BAND_CONFIG).isNotEqualTo(amBandConfigFromBuilder);
    }

    @Test
    public void getId_forModuleProperties() {
        assertWithMessage("Properties id")
                .that(AMFM_PROPERTIES.getId()).isEqualTo(PROPERTIES_ID);
    }

    @Test
    public void getServiceName_forModuleProperties() {
        assertWithMessage("Properties service name")
                .that(AMFM_PROPERTIES.getServiceName()).isEqualTo(SERVICE_NAME);
    }

    @Test
    public void getClassId_forModuleProperties() {
        assertWithMessage("Properties class ID")
                .that(AMFM_PROPERTIES.getClassId()).isEqualTo(CLASS_ID);
    }

    @Test
    public void getImplementor_forModuleProperties() {
        assertWithMessage("Properties implementor")
                .that(AMFM_PROPERTIES.getImplementor()).isEqualTo(IMPLEMENTOR);
    }

    @Test
    public void getProduct_forModuleProperties() {
        assertWithMessage("Properties product")
                .that(AMFM_PROPERTIES.getProduct()).isEqualTo(PRODUCT);
    }

    @Test
    public void getVersion_forModuleProperties() {
        assertWithMessage("Properties version")
                .that(AMFM_PROPERTIES.getVersion()).isEqualTo(VERSION);
    }

    @Test
    public void getSerial_forModuleProperties() {
        assertWithMessage("Serial properties")
                .that(AMFM_PROPERTIES.getSerial()).isEqualTo(SERIAL);
    }

    @Test
    public void getNumTuners_forModuleProperties() {
        assertWithMessage("Number of tuners in properties")
                .that(AMFM_PROPERTIES.getNumTuners()).isEqualTo(NUM_TUNERS);
    }

    @Test
    public void getNumAudioSources_forModuleProperties() {
        assertWithMessage("Number of audio sources in properties")
                .that(AMFM_PROPERTIES.getNumAudioSources()).isEqualTo(NUM_AUDIO_SOURCES);
    }

    @Test
    public void isInitializationRequired_forModuleProperties() {
        assertWithMessage("Initialization required in properties")
                .that(AMFM_PROPERTIES.isInitializationRequired())
                .isEqualTo(IS_INITIALIZATION_REQUIRED);
    }

    @Test
    public void isCaptureSupported_forModuleProperties() {
        assertWithMessage("Capture support in properties")
                .that(AMFM_PROPERTIES.isCaptureSupported()).isEqualTo(IS_CAPTURE_SUPPORTED);
    }

    @Test
    public void isBackgroundScanningSupported_forModuleProperties() {
        assertWithMessage("Background scan support in properties")
                .that(AMFM_PROPERTIES.isBackgroundScanningSupported())
                .isEqualTo(IS_BG_SCAN_SUPPORTED);
    }

    @Test
    public void isProgramTypeSupported_withSupportedType_forModuleProperties() {
        assertWithMessage("AM/FM frequency type radio support in properties")
                .that(AMFM_PROPERTIES.isProgramTypeSupported(
                        ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY))
                .isTrue();
    }

    @Test
    public void isProgramTypeSupported_withNonSupportedType_forModuleProperties() {
        assertWithMessage("DAB frequency type radio support in properties")
                .that(AMFM_PROPERTIES.isProgramTypeSupported(
                        ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY)).isFalse();
    }

    @Test
    public void isProgramIdentifierSupported_withSupportedIdentifier_forModuleProperties() {
        assertWithMessage("AM/FM frequency identifier radio support in properties")
                .that(AMFM_PROPERTIES.isProgramIdentifierSupported(
                        ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY)).isTrue();
    }

    @Test
    public void isProgramIdentifierSupported_withNonSupportedIdentifier_forModuleProperties() {
        assertWithMessage("DAB frequency identifier radio support in properties")
                .that(AMFM_PROPERTIES.isProgramIdentifierSupported(
                        ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY)).isFalse();
    }

    @Test
    public void getDabFrequencyTable_forModuleProperties() {
        assertWithMessage("Properties DAB frequency table")
                .that(AMFM_PROPERTIES.getDabFrequencyTable()).isNull();
    }

    @Test
    public void getVendorInfo_forModuleProperties() {
        assertWithMessage("Properties vendor info")
                .that(AMFM_PROPERTIES.getVendorInfo()).isEmpty();
    }

    @Test
    public void getBands_forModuleProperties() {
        assertWithMessage("Properties bands")
                .that(AMFM_PROPERTIES.getBands()).asList()
                .containsExactly(AM_BAND_DESCRIPTOR, FM_BAND_DESCRIPTOR);
    }

    @Test
    public void equals_withSameProperties_returnsTrue() {
        RadioManager.ModuleProperties propertiesCompared = createAmFmProperties();

        assertWithMessage("The same module properties")
                .that(AMFM_PROPERTIES).isEqualTo(propertiesCompared);
    }

    @Test
    public void equals_withModulePropertiesOfDifferentIds_returnsFalse() {
        RadioManager.ModuleProperties propertiesDab = new RadioManager.ModuleProperties(
                PROPERTIES_ID + 1, SERVICE_NAME, CLASS_ID, IMPLEMENTOR, PRODUCT, VERSION,
                SERIAL, NUM_TUNERS, NUM_AUDIO_SOURCES, IS_INITIALIZATION_REQUIRED,
                IS_CAPTURE_SUPPORTED, /* bands= */ null, IS_BG_SCAN_SUPPORTED,
                SUPPORTED_PROGRAM_TYPES, SUPPORTED_IDENTIFIERS_TYPES, /* dabFrequencyTable= */ null,
                /* vendorInfo= */ null);

        assertWithMessage("Module properties of different id")
                .that(AMFM_PROPERTIES).isNotEqualTo(propertiesDab);
    }

    @Test
    public void getSelector_forProgramInfo() {
        assertWithMessage("Selector of DAB program info")
                .that(DAB_PROGRAM_INFO.getSelector()).isEqualTo(DAB_SELECTOR);
    }

    @Test
    public void getLogicallyTunedTo_forProgramInfo() {
        assertWithMessage("Identifier logically tuned to in DAB program info")
                .that(DAB_PROGRAM_INFO.getLogicallyTunedTo()).isEqualTo(DAB_FREQUENCY_IDENTIFIER);
    }

    @Test
    public void getPhysicallyTunedTo_forProgramInfo() {
        assertWithMessage("Identifier physically tuned to DAB program info")
                .that(DAB_PROGRAM_INFO.getPhysicallyTunedTo()).isEqualTo(DAB_SID_EXT_IDENTIFIER);
    }

    @Test
    public void getRelatedContent_forProgramInfo() {
        assertWithMessage("Related contents of DAB program info")
                .that(DAB_PROGRAM_INFO.getRelatedContent())
                .containsExactly(DAB_SID_EXT_IDENTIFIER_RELATED);
    }

    @Test
    public void getChannel_forProgramInfo() {
        assertWithMessage("Main channel of DAB program info")
                .that(DAB_PROGRAM_INFO.getChannel()).isEqualTo(0);
    }

    @Test
    public void getSubChannel_forProgramInfo() {
        assertWithMessage("Sub channel of DAB program info")
                .that(DAB_PROGRAM_INFO.getSubChannel()).isEqualTo(0);
    }

    @Test
    public void isTuned_forProgramInfo() {
        assertWithMessage("Tuned status of DAB program info")
                .that(DAB_PROGRAM_INFO.isTuned()).isTrue();
    }

    @Test
    public void isStereo_forProgramInfo() {
        assertWithMessage("Stereo support in DAB program info")
                .that(DAB_PROGRAM_INFO.isStereo()).isTrue();
    }

    @Test
    public void isDigital_forProgramInfo() {
        assertWithMessage("Digital DAB program info")
                .that(DAB_PROGRAM_INFO.isDigital()).isTrue();
    }

    @Test
    public void isLive_forProgramInfo() {
        assertWithMessage("Live status of DAB program info")
                .that(DAB_PROGRAM_INFO.isLive()).isTrue();
    }

    @Test
    public void isMuted_forProgramInfo() {
        assertWithMessage("Muted status of DAB program info")
                .that(DAB_PROGRAM_INFO.isMuted()).isFalse();
    }

    @Test
    public void isTrafficProgram_forProgramInfo() {
        assertWithMessage("Traffic program support in DAB program info")
                .that(DAB_PROGRAM_INFO.isTrafficProgram()).isFalse();
    }

    @Test
    public void isTrafficAnnouncementActive_forProgramInfo() {
        assertWithMessage("Active traffic announcement for DAB program info")
                .that(DAB_PROGRAM_INFO.isTrafficAnnouncementActive()).isFalse();
    }

    @Test
    public void getSignalStrength_forProgramInfo() {
        assertWithMessage("Signal strength of DAB program info")
                .that(DAB_PROGRAM_INFO.getSignalStrength()).isEqualTo(SIGNAL_QUALITY);
    }

    @Test
    public void getMetadata_forProgramInfo() {
        assertWithMessage("Metadata of DAB program info")
                .that(DAB_PROGRAM_INFO.getMetadata()).isEqualTo(METADATA);
    }

    @Test
    public void getVendorInfo_forProgramInfo() {
        assertWithMessage("Vendor info of DAB program info")
                .that(DAB_PROGRAM_INFO.getVendorInfo()).isEmpty();
    }

    @Test
    public void equals_withSameProgramInfo_returnsTrue() {
        RadioManager.ProgramInfo dabProgramInfoCompared = createDabProgramInfo(DAB_SELECTOR);

        assertWithMessage("The same program info")
                .that(dabProgramInfoCompared).isEqualTo(DAB_PROGRAM_INFO);
    }

    @Test
    public void equals_withSameProgramInfoOfDifferentSecondaryIdSelectors_returnsFalse() {
        ProgramSelector dabSelectorCompared = new ProgramSelector(
                ProgramSelector.PROGRAM_TYPE_DAB, DAB_SID_EXT_IDENTIFIER,
                new ProgramSelector.Identifier[]{DAB_FREQUENCY_IDENTIFIER},
                /* vendorIds= */ null);
        RadioManager.ProgramInfo dabProgramInfoCompared = createDabProgramInfo(dabSelectorCompared);

        assertWithMessage("Program info with different secondary id selectors")
                .that(DAB_PROGRAM_INFO).isNotEqualTo(dabProgramInfoCompared);
    }

    private static RadioManager.ModuleProperties createAmFmProperties() {
        return new RadioManager.ModuleProperties(PROPERTIES_ID, SERVICE_NAME, CLASS_ID,
                IMPLEMENTOR, PRODUCT, VERSION, SERIAL, NUM_TUNERS, NUM_AUDIO_SOURCES,
                IS_INITIALIZATION_REQUIRED, IS_CAPTURE_SUPPORTED,
                new RadioManager.BandDescriptor[]{AM_BAND_DESCRIPTOR, FM_BAND_DESCRIPTOR},
                IS_BG_SCAN_SUPPORTED, SUPPORTED_PROGRAM_TYPES, SUPPORTED_IDENTIFIERS_TYPES,
                /* dabFrequencyTable= */ null, /* vendorInfo= */ null);
    }

    private static RadioManager.FmBandDescriptor createFmBandDescriptor() {
        return new RadioManager.FmBandDescriptor(REGION, RadioManager.BAND_FM, FM_LOWER_LIMIT,
                FM_UPPER_LIMIT, FM_SPACING, STEREO_SUPPORTED, RDS_SUPPORTED, TA_SUPPORTED,
                AF_SUPPORTED, EA_SUPPORTED);
    }

    private static RadioManager.AmBandDescriptor createAmBandDescriptor() {
        return new RadioManager.AmBandDescriptor(REGION, RadioManager.BAND_AM, AM_LOWER_LIMIT,
                AM_UPPER_LIMIT, AM_SPACING, STEREO_SUPPORTED);
    }

    private static RadioManager.FmBandConfig createFmBandConfig() {
        return new RadioManager.FmBandConfig(createFmBandDescriptor());
    }

    private static RadioManager.AmBandConfig createAmBandConfig() {
        return new RadioManager.AmBandConfig(createAmBandDescriptor());
    }

    private static RadioMetadata createMetadata() {
        RadioMetadata.Builder metadataBuilder = new RadioMetadata.Builder();
        return metadataBuilder.putString(RadioMetadata.METADATA_KEY_ARTIST, "artistTest").build();
    }

    private static RadioManager.ProgramInfo createDabProgramInfo(ProgramSelector selector) {
        return new RadioManager.ProgramInfo(selector, DAB_FREQUENCY_IDENTIFIER,
                DAB_SID_EXT_IDENTIFIER, Arrays.asList(DAB_SID_EXT_IDENTIFIER_RELATED), INFO_FLAGS,
                SIGNAL_QUALITY, METADATA, /* vendorInfo= */ null);
    }

}
