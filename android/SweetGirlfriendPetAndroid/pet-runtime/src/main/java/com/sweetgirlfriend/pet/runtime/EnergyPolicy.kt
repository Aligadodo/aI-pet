package com.sweetgirlfriend.pet.runtime

object EnergyPolicy {
    fun frameRate(
        profile: EnergyProfile,
        level: PetActivityLevel,
        screenInteractive: Boolean,
    ): Int {
        if (!screenInteractive) return 0
        return when (profile) {
            EnergyProfile.SMOOTH -> when (level) {
                PetActivityLevel.INTERACTING -> 20
                PetActivityLevel.ACTIVE -> 16
                PetActivityLevel.IDLE -> 6
                PetActivityLevel.SLEEP -> 1
            }

            EnergyProfile.ADAPTIVE -> when (level) {
                PetActivityLevel.INTERACTING -> 16
                PetActivityLevel.ACTIVE -> 12
                PetActivityLevel.IDLE -> 4
                PetActivityLevel.SLEEP -> 1
            }

            EnergyProfile.SAVER -> when (level) {
                PetActivityLevel.INTERACTING -> 12
                PetActivityLevel.ACTIVE -> 8
                PetActivityLevel.IDLE -> 2
                PetActivityLevel.SLEEP -> 1
            }
        }
    }
}
