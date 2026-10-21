package com.andrerinas.openheadunit.aap

/** Prefer the associated station channel only on the initial native group attempt. */
object NativeGroupFrequencyPolicy {
    // Non-DFS 20 MHz primary channels; let the driver enforce its regulatory domain.
    private val NON_DFS_5G_CHANNELS = listOf(36, 40, 44, 48, 149, 153, 157, 161, 165)

    private fun channelOf(frequency: Int): Int =
        if (frequency >= 5000 && frequency % 5 == 0) (frequency - 5000) / 5 else 0

    fun preferredFrequency(stationFrequency: Int, associated: Boolean, force24: Boolean,
                           retryCount: Int, recreateCount: Int): Int {
        if (!associated || force24 || retryCount != 0 || recreateCount != 0) return 0
        return if (channelOf(stationFrequency) in NON_DFS_5G_CHANNELS) stationFrequency else 0
    }

    /**
     * ICCOA accepts only a channel whitelist and sends element 0 to the phone as its default 5G
     * channel. Putting the station's channel first keeps the P2P group on the channel the car is
     * already using; an empty list makes the SDK fall back to 165 (5825 MHz) and time-slice
     * between two channels, which shows up as map stutter.
     */
    fun allowed5gChannels(stationFrequency: Int): IntArray {
        val station = channelOf(stationFrequency)
        val ordered = if (station in NON_DFS_5G_CHANNELS) listOf(station) + NON_DFS_5G_CHANNELS
                else NON_DFS_5G_CHANNELS
        return ordered.distinct().toIntArray()
    }
}
