// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.data.art.toSongArt
import com.kaislate.veldtplayer.data.library.DisplayNames
import com.kaislate.veldtplayer.data.library.LibraryKeys
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.ui.components.AlbumCard
import com.kaislate.veldtplayer.ui.components.ArtImage
import com.kaislate.veldtplayer.ui.components.ArtPlaceholder
import com.kaislate.veldtplayer.ui.components.SongRow
import com.kaislate.veldtplayer.ui.components.paletteWash
import com.kaislate.veldtplayer.ui.motion.Motion
import com.kaislate.veldtplayer.ui.motion.artistArtKey
import com.kaislate.veldtplayer.ui.motion.rememberReducedMotion
import com.kaislate.veldtplayer.ui.motion.sharedArt
import com.kaislate.veldtplayer.ui.motion.staggeredEntrance
import com.kaislate.veldtplayer.ui.theme.ColorExtractor
import com.kaislate.veldtplayer.ui.theme.DominantColors

/** The circular portrait on an artist card, and the card it is centred in. */
private val ARTIST_PORTRAIT_SIZE = 88.dp
private val ARTIST_CARD_WIDTH = 104.dp

/** The emblem glyph when the term itself has no letter or digit to draw. */
private const val NO_MATCH_GLYPH = '?'

/**
 * Search across the whole library.
 *
 * The two non-song sections are SHELVES of artwork rather than lists of text. A vertical
 * list of bare artist and album names would be the one surface in an art-forward player
 * with nothing to look at, and it would also push the songs — usually what the user is
 * after — an unbounded distance down the scroll. A shelf caps each section at one row high.
 *
 * Every cover here is a morph source into the detail screen it opens, and each is resolved
 * over the FULL library rather than over the matched songs: `coverTrack()` is
 * order-independent but not set-independent, so choosing from a search's partial track list
 * would hand the two ends of the transition different bitmaps and turn the morph into a
 * swap.
 */
@Composable
fun SearchScreen(
    vm: BrowseViewModel,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    // collectAsStateWithLifecycle, not collectAsState: every VM flow here is
    // WhileSubscribed, and a backgrounded screen must let the upstream stop.
    val query by vm.query.collectAsStateWithLifecycle()
    val settled by vm.settledQuery.collectAsStateWithLifecycle()
    // The ROWS AND THE TERM THEY ANSWER, from one value. Not vm.results: an empty list on
    // its own cannot say whether the term matched nothing or has not reached Room yet.
    val answer by vm.answered.collectAsStateWithLifecycle()
    val songs = answer.songs
    val albums by vm.resultAlbums.collectAsStateWithLifecycle()
    val artists by vm.resultArtists.collectAsStateWithLifecycle()
    // The whole library, for the shelves' artwork ONLY — see the covers note above.
    val library by vm.songs.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val reduced = rememberReducedMotion()
    val palette = ColorExtractor.extract(null)

    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    // Opening search means wanting to type: the field takes focus, which brings the
    // keyboard with it, so reaching the screen costs one tap rather than two.
    //
    // ONCE, though — hence rememberSaveable rather than a bare LaunchedEffect(Unit).
    // This is a plain `composable` destination, so opening an album from a result DISPOSES
    // this screen and popping back RECOMPOSES it; an unguarded effect would re-run there
    // and shove the keyboard over the bottom half of the results the user came back to
    // READ. The flag rides the back stack entry's saved state, so it survives that round
    // trip and dies with the entry — the next fresh trip to search opens typing-ready.
    var focusClaimed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!focusClaimed) {
            focusClaimed = true
            focus.requestFocus()
        }
    }

    // Scrolling means reading, not typing. Dropping the keyboard on the first drag gives
    // the results back half the screen without the user having to aim at a Back gesture.
    LaunchedEffect(listState, keyboard) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling -> if (scrolling) keyboard?.hide() }
    }

    val coverByAlbum: Map<String, Song?> = remember(library) {
        library.groupBy { LibraryKeys.albumKey(it) }.mapValues { (_, rows) -> rows.coverTrack() }
    }
    val portraitByArtist: Map<String, Song?> = remember(library) {
        library.groupBy { LibraryKeys.artistKey(it) }.mapValues { (_, rows) -> rows.coverTrack() }
    }

    // Insets are split as on every browse surface: the bottom one becomes the scrollable's
    // contentPadding so results pass under the translucent navigation bar, while the top and
    // sides stay padding modifiers because the search field must not slide under the clock.
    val direction = LocalLayoutDirection.current
    // ...but the bottom one here has to account for the keyboard, which is this screen's
    // PRIMARY state. `contentPadding` comes from a Scaffold whose contentWindowInsets are
    // systemBars, so it is the navigation bar's height whether or not the keyboard is up;
    // imePadding() below already lifts the whole column clear of the keyboard, and adding
    // the nav-bar height on top of that reserves ~80dp of dead space above the IME. So the
    // list only pads out whatever the keyboard does NOT already cover — a max(), expressed
    // as "the part of the nav bar the keyboard is not standing on".
    val imeInset = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val bottomInset =
        (contentPadding.calculateBottomPadding() - imeInset).coerceAtLeast(0.dp)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = contentPadding.calculateStartPadding(direction),
                top = contentPadding.calculateTopPadding(),
                end = contentPadding.calculateEndPadding(direction),
            )
            // The window does not resize for the keyboard under edge-to-edge, so without
            // this the results the user is typing toward sit behind it.
            .imePadding(),
    ) {
        SearchField(
            query = query,
            onQueryChange = vm::setQuery,
            onBack = onBack,
            onSubmit = { keyboard?.hide() },
            focusRequester = focus,
        )

        val nothingFound = songs.isEmpty() && albums.isEmpty() && artists.isEmpty()
        // True while the field has moved on from the settled term (still typing) OR the
        // settled term has not come back from Room yet. See searchPending.
        val pending = searchPending(query = query, settled = settled, answeredTerm = answer.term)
        val messagePadding = PaddingValues(bottom = bottomInset)

        when {
            // Blank field: an empty results list is the honest answer, not a finding.
            query.isBlank() -> BrowseMessage(
                palette = palette,
                title = "Search your library",
                body = "Find a song, a record or an artist by name. Results narrow as you type.",
                contentPadding = messagePadding,
                modifier = Modifier.weight(1f),
                emblem = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(paletteWash(palette)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = palette.onBg.copy(alpha = 0.75f),
                            modifier = Modifier.size(44.dp),
                        )
                    }
                },
            )

            !nothingFound -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = bottomInset + LIST_AIR),
            ) {
                if (artists.isNotEmpty()) {
                    item(key = "artists-label") { SectionLabel("Artists") }
                    item(key = "artists") {
                        Shelf {
                            itemsIndexed(artists, key = { _, it -> it.key }) { index, artist ->
                                ArtistCard(
                                    artist = artist,
                                    portrait = portraitByArtist[artist.key],
                                    palette = palette,
                                    onClick = { onOpenArtist(artist.key) },
                                    modifier = Modifier.staggeredEntrance(index, reduced),
                                )
                            }
                        }
                    }
                }
                if (albums.isNotEmpty()) {
                    item(key = "albums-label") { SectionLabel("Albums") }
                    item(key = "albums") {
                        Shelf {
                            itemsIndexed(albums, key = { _, it -> it.key }) { index, album ->
                                // The caption names the owner the album was KEYED under, so
                                // two same-titled records are told apart on the shelf.
                                AlbumCard(
                                    albumKey = album.key,
                                    title = DisplayNames.album(album.name),
                                    cover = coverByAlbum[album.key],
                                    palette = palette,
                                    onClick = { onOpenAlbum(album.key) },
                                    modifier = Modifier.staggeredEntrance(index, reduced),
                                    caption = DisplayNames.albumArtist(
                                        album.albumArtist,
                                        coverByAlbum[album.key]?.artist,
                                    ),
                                )
                            }
                        }
                    }
                }
                if (songs.isNotEmpty()) {
                    item(key = "songs-label") { SectionLabel("Songs") }
                    itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                        // Play-in-context: the whole result set becomes the queue, from here.
                        SongRow(
                            song = song,
                            palette = palette,
                            onClick = { vm.play(songs, index) },
                            modifier = Modifier.staggeredEntrance(index, reduced),
                        )
                    }
                }
            }

            // Nothing matched AND the library is still filling. "No matches" would be a
            // lie about a catalogue that does not exist yet — the same three-way split the
            // browse tabs draw.
            scanning -> ScanningState(
                palette = palette,
                contentPadding = messagePadding,
                modifier = Modifier.weight(1f),
            )

            // Nothing matched, but the term on screen has not been run yet. Held blank for
            // the length of the debounce rather than announcing a verdict on a query that
            // is still being typed.
            pending -> Spacer(Modifier.weight(1f))

            else -> BrowseMessage(
                palette = palette,
                title = "No matches for “$settled”",
                body = "Nothing in the library carries that in a title, a record or an " +
                    "artist name.",
                contentPadding = messagePadding,
                modifier = Modifier.weight(1f),
                // The term's own initial, drawn exactly as an art-less cover is drawn, so
                // even the dead end is made of the same material as the rest of the app.
                emblem = {
                    ArtPlaceholder(
                        initial = settled.firstOrNull { it.isLetterOrDigit() } ?: NO_MATCH_GLYPH,
                        palette = palette,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
        }
    }
}

/**
 * The search bar: back, field, clear. The field is a filled pill rather than an outlined
 * box — an outline drawn a few dp under the status bar reads as a seam across the top of
 * the screen, while a tonal container reads as a slot the text sits in.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = SIDE_MARGIN, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            singleLine = true,
            shape = CircleShape,
            placeholder = { Text("Songs, albums, artists") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                // Fades rather than appearing: the affordance arrives with the first
                // keystroke, and a hard cut there draws the eye away from what is typed.
                AnimatedVisibility(
                    visible = query.isNotEmpty(),
                    enter = fadeIn(animationSpec = Motion.gentle),
                    exit = fadeOut(animationSpec = Motion.gentle),
                ) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // The results are already live, so Search only puts the keyboard away.
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        )
    }
}

/** One artist on the shelf: the portrait the Artists tab drew, and their name under it. */
@Composable
private fun ArtistCard(
    artist: Artist,
    portrait: Song?,
    palette: DominantColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = DisplayNames.artist(artist.name)
    Column(
        modifier = modifier
            .width(ARTIST_CARD_WIDTH)
            // Clip before clickable so the ripple stops at the card's corners.
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A fixed size, never unbounded: ArtImage's loading state fills its parent and
        // would otherwise collapse to ~0 and jump when the bitmap arrives.
        ArtImage(
            art = portrait?.toSongArt(),
            palette = palette,
            initial = name.firstOrNull { it.isLetterOrDigit() } ?: '♪',
            modifier = Modifier
                .size(ARTIST_PORTRAIT_SIZE)
                .sharedArt(artistArtKey(artist.key))
                .clip(CircleShape),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
