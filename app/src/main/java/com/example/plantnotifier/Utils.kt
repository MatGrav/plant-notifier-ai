package com.example.plantnotifier

fun getDaysRemaining(lastWatered: Long, wateringDays: Int): Int {
    val nextWatering = lastWatered + (wateringDays.toLong() * 24 * 60 * 60 * 1000)
    val remainingMillis = nextWatering - System.currentTimeMillis()
    return (remainingMillis / (24 * 60 * 60 * 1000)).toInt()
}