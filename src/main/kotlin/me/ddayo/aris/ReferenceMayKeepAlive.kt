package me.ddayo.aris


@RequiresOptIn(
    message = "Allocated reference may cause unexpected behaviour. Make sure all references are invalidated. I highly recommend recreating entire engine for unloading action.",
    level = RequiresOptIn.Level.ERROR
)
annotation class ReferenceMayKeepAlive
