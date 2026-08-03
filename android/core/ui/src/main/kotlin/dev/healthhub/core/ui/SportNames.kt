package dev.healthhub.core.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.healthhub.core.model.Sport

/**
 * What a sport is called, in the reader's language.
 *
 * `Format.sportLabel` capitalises the stored slug, which produces "Cycling" in every language
 * the app is read in — and next to a date the platform localises, that is the mixed-language
 * screen an athlete actually sees: "вс 2 авг." above "Cycling · Заезд Cycplus M2".
 *
 * Keyed off [Sport] rather than off the raw string, so an unlisted slug lands on the same
 * `OTHER` the model already maps it to. A slug with no name of its own falls back to the slug
 * itself rather than to nothing — Principle VI: a type the app cannot name is still a type it
 * must not hide.
 */
@StringRes
fun sportNameRes(slug: String): Int = when (Sport.fromSlug(slug)) {
    Sport.RUNNING -> R.string.sport_running
    Sport.TRAIL_RUNNING -> R.string.sport_trail_running
    Sport.WALKING -> R.string.sport_walking
    Sport.HIKING -> R.string.sport_hiking
    Sport.CYCLING -> R.string.sport_cycling
    Sport.MOUNTAIN_BIKING -> R.string.sport_mountain_biking
    Sport.EBIKING -> R.string.sport_ebiking
    Sport.SWIMMING -> R.string.sport_swimming
    Sport.ROWING -> R.string.sport_rowing
    Sport.SKIING -> R.string.sport_skiing
    Sport.SNOWBOARDING -> R.string.sport_snowboarding
    Sport.SKATING -> R.string.sport_skating
    Sport.STRENGTH -> R.string.sport_strength
    Sport.YOGA -> R.string.sport_yoga
    Sport.OTHER -> R.string.sport_other
}

@Composable
fun sportName(slug: String): String {
    // `OTHER` is where every unrecognised slug lands, and rendering all of them as "Other"
    // would hide a sport the athlete really recorded behind a word that says nothing. The slug
    // is not a translation, but it is the truth, which matters more.
    if (Sport.fromSlug(slug) == Sport.OTHER && slug != Sport.OTHER.slug) {
        return Format.sportLabel(slug)
    }
    return stringResource(sportNameRes(slug))
}

/**
 * The line under a card's headline, localised.
 *
 * Health Connect writes no title of its own, so the sport is stored as one and a naive join
 * reads "Ходьба · Walking". The title earns its own words only when somebody — the athlete, or
 * the app that recorded it — actually wrote one, and the comparison is against the *slug*
 * rather than against the translated name, because the stored title is never translated.
 */
@Composable
fun sportAndTitle(slug: String, title: String): String {
    val name = sportName(slug)
    return if (title.isBlank() || title.equals(slug, ignoreCase = true)) name else "$name · $title"
}
