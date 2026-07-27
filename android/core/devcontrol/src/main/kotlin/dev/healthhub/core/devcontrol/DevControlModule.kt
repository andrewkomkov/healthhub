package dev.healthhub.core.devcontrol

import dagger.Module
import dagger.multibindings.Multibinds
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Declares the command set so the graph is valid even before any module contributes to it.
 *
 * This is the same multibinding shape as navigation contributions: a feature adds its
 * commands by binding into the set, and neither this file nor the dispatcher changes.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DevControlModule {

    @Multibinds
    abstract fun devCommands(): Set<DevCommand>
}
