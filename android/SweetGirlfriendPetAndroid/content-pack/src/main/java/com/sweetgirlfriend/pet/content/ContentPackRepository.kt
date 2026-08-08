package com.sweetgirlfriend.pet.content

import com.sweetgirlfriend.pet.runtime.AnimationClip
import com.sweetgirlfriend.pet.runtime.BehaviorSpec
import com.sweetgirlfriend.pet.runtime.CharacterGameKit
import com.sweetgirlfriend.pet.runtime.PackDescriptor
import com.sweetgirlfriend.pet.runtime.PackSettingDefinition
import com.sweetgirlfriend.pet.runtime.PetTask
import java.io.InputStream

interface ContentPackRepository {
    fun listPacks(): List<PackDescriptor>
    fun availableActions(packId: String): Set<String>
    fun loadClip(packId: String, action: String): AnimationClip
    fun loadGameKit(packId: String): CharacterGameKit
    fun loadBehavior(packId: String): BehaviorSpec
    fun loadTasks(packId: String): List<PetTask>
    fun loadPackSettings(packId: String): List<PackSettingDefinition>
    fun randomDialogue(packId: String, event: String, fallback: String = "我在这里陪你。"): String
    fun openAsset(path: String): InputStream
    fun validate(packId: String): List<String>
}
