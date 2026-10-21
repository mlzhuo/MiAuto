package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeGroupFrequencyPolicyTest {
    @Test fun prefersAssociatedNonDfsChannel() {
        for (frequency in listOf(5180, 5200, 5240, 5745, 5825))
            assertEquals(frequency, NativeGroupFrequencyPolicy.preferredFrequency(frequency, true, false, 0, 0))
    }
    @Test fun leavesUnsupportedAndDfsChannelsToDriver() {
        for (frequency in listOf(0, 2412, 5260, 5500, 5181, 5955))
            assertEquals(0, NativeGroupFrequencyPolicy.preferredFrequency(frequency, true, false, 0, 0))
    }
    @Test fun preservesForcedBandAndFallsBackOnRecovery() {
        assertEquals(0, NativeGroupFrequencyPolicy.preferredFrequency(5200, false, false, 0, 0))
        assertEquals(0, NativeGroupFrequencyPolicy.preferredFrequency(5200, true, true, 0, 0))
        assertEquals(0, NativeGroupFrequencyPolicy.preferredFrequency(5200, true, false, 1, 0))
        assertEquals(0, NativeGroupFrequencyPolicy.preferredFrequency(5200, true, false, 0, 1))
    }
    @Test fun allowedListPutsStationChannelFirst() {
        assertEquals(listOf(44, 36, 40, 48, 149, 153, 157, 161, 165),
            NativeGroupFrequencyPolicy.allowed5gChannels(5220).toList())
    }
    @Test fun allowedListIsNeverEmpty() {
        for (frequency in listOf(0, 2412, 5260)) {
            val channels = NativeGroupFrequencyPolicy.allowed5gChannels(frequency)
            assertEquals(36, channels.first())
            assertEquals(9, channels.size)
        }
    }
}
