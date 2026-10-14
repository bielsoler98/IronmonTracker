package com.biel.ironmontracker.randomizer

import com.dabomstew.pkrandom.FileFunctions
import com.dabomstew.pkrandom.RandomSource
import com.dabomstew.pkrandom.Randomizer
import com.dabomstew.pkrandom.Settings
import com.dabomstew.pkrandom.romhandlers.Gen1RomHandler
import com.dabomstew.pkrandom.romhandlers.Gen2RomHandler
import com.dabomstew.pkrandom.romhandlers.Gen3RomHandler
import com.dabomstew.pkrandom.romhandlers.Gen4RomHandler
import com.dabomstew.pkrandom.romhandlers.Gen5RomHandler
import com.dabomstew.pkrandom.romhandlers.Gen6RomHandler
import com.dabomstew.pkrandom.romhandlers.Gen7RomHandler
import com.dabomstew.pkrandom.romhandlers.RomHandler
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream

/**
 * Headless wrapper around the vendored com.dabomstew.pkrandom engine (ported
 * from PokemonRandomizerZX). CPU-bound and can take several seconds — always
 * call from a background dispatcher.
 */
object RandomizerBridge {

    private val factories: Array<RomHandler.Factory> = arrayOf(
        Gen1RomHandler.Factory(),
        Gen2RomHandler.Factory(),
        Gen3RomHandler.Factory(),
        Gen4RomHandler.Factory(),
        Gen5RomHandler.Factory(),
        Gen6RomHandler.Factory(),
        Gen7RomHandler.Factory(),
    )

    fun randomize(sourceRomPath: String, outputRomPath: String, settings: Settings) {
        val bundle = java.util.ResourceBundle.getBundle("com/dabomstew/pkrandom/newgui/Bundle")

        val romHandler: RomHandler = factories.firstNotNullOfOrNull { factory ->
            if (factory.isLoadable(sourceRomPath)) {
                factory.create(RandomSource.instance()).also { it.loadRom(sourceRomPath) }
            } else {
                null
            }
        } ?: throw IOException("Unsupported or unrecognized ROM format: $sourceRomPath")

        settings.customNames = FileFunctions.getCustomNames()

        val seed = RandomSource.pickSeed()
        val logStream = PrintStream(ByteArrayOutputStream(), false, "UTF-8")
        try {
            Randomizer(settings, romHandler, bundle, false).randomize(outputRomPath, logStream, seed)
        } finally {
            logStream.close()
        }
    }
}
