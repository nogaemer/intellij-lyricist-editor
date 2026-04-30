package de.nogaemer.intellij_lyricist_editor_test_project

import cafe.adriel.lyricist.LyricistStrings

data class Strings(
    val common: CommonStrings,
    val home: HomeStrings,
    val game: GameStrings,
)

data class CommonStrings(
    val appName: String,
    val ok: String,
    val cancel: String,
    val save: String,
)

data class HomeStrings(
    val title: String,
    val subtitle: String,
    val startButton: String,
)

data class GameStrings(
    val roundLabel: String,
    val scoreLabel: String,
    val playerGreeting: (String) -> String,
    val scoreFormat: (Int, Int) -> String,
    val level: LevelStrings,
)

data class LevelStrings(
    val current: String,
    val next: String,
    val details: LevelDetails,
)

data class LevelDetails(
    val name: String,
    val test: String,
    val description: String,
    val bonus: BonusInfo,
)

data class BonusInfo(
    val title: String,
    val multiplierFormat: (Int) -> String,
    val test: (Int, String) -> String,
)

@LyricistStrings(languageTag = "en", default = true)
val EnStrings = Strings(
    common = CommonStrings(
        appName = "TestApp",
        ok = "OK",
        cancel = "Cancel",
        save = "Save",
    ),
    home = HomeStrings(
        title = "Welcome",
        subtitle = "This is a test project for the i18n plugin",
        startButton = "Start Game",
    ),
    game = GameStrings(
        roundLabel = "Round",
        scoreLabel = "Scores",
        playerGreeting = { name -> "Hey $name, good luck!" },
        scoreFormat = { correct, total -> "$correct / $total" },
        level = LevelStrings(
            current = "Current Level: Forest",
            next = "Next Level: Cave",
            details = LevelDetails(
                name = "Forest of Beginnings",
                description = "A dense forest with simple enemies.",
                test = ",",
                bonus = BonusInfo(
                    title = "Bonus stage!",
                    multiplierFormat = { multiplier -> "x$multiplier points" },
                    test = { test, test2 -> "$test + $test2" }
                )
            )
        )
    ),
)

@LyricistStrings(languageTag = "de")
val DeStrings = Strings(
    common = CommonStrings(
        appName = "TestApp",
        ok = "OK",
        cancel = "Abbrechen",
        save = "Speichern",
    ),
    home = HomeStrings(
        title = "Willkommen",
        subtitle = "Das ist ein Testprojekt für das i18n-Plugin",
        startButton = "Spiel starten",
    ),
    game = GameStrings(
        roundLabel = "Runde",
        scoreLabel = "Punkte",
        playerGreeting = { name -> "Hey $name, viel Erfolg!" },
        scoreFormat = { correct, total -> "$correct / $total" },
        level = LevelStrings(
            current = "Aktuelles Level: Wald",
            next = "Nächstes Level: Höhle",
            details = LevelDetails(
                name = "Wald der Anfänge",
                description = "Ein dichter Wald mit einfachen Gegnern.",
                test = ",",
                bonus = BonusInfo(
                    title = "Bonuslevel!",
                    multiplierFormat = { multiplier -> "$multiplier-fache Punkte" },
                    test = { test, test2 -> "$test + $test2" }
                )
            )
        )
    ),
)