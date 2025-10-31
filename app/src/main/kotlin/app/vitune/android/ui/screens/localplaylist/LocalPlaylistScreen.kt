package app.jaytune.android.ui.screens.localplaylist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import app.jaytune.android.Database
import app.jaytune.android.R
import app.jaytune.android.models.Playlist
import app.jaytune.android.models.Song
import app.jaytune.android.ui.components.themed.Scaffold
import app.jaytune.android.ui.components.themed.adaptiveThumbnailContent
import app.jaytune.android.ui.screens.GlobalRoutes
import app.jaytune.android.ui.screens.Route
import app.jaytune.compose.persist.PersistMapCleanup
import app.jaytune.compose.persist.persist
import app.jaytune.compose.persist.persistList
import app.jaytune.compose.routing.RouteHandler
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

@Route
@Composable
fun LocalPlaylistScreen(playlistId: Long) {
    val saveableStateHolder = rememberSaveableStateHolder()

    PersistMapCleanup(prefix = "localPlaylist/$playlistId/")

    RouteHandler {
        GlobalRoutes()

        Content {
            var playlist by persist<Playlist?>("localPlaylist/$playlistId/playlist")
            var songs by persistList<Song>("localPlaylist/$playlistId/songs")

            LaunchedEffect(Unit) {
                Database
                    .playlist(playlistId)
                    .filterNotNull()
                    .distinctUntilChanged()
                    .collect { playlist = it }
            }

            LaunchedEffect(Unit) {
                Database
                    .playlistSongs(playlistId)
                    .distinctUntilChanged()
                    .collect { songs = it.toImmutableList() }
            }

            val thumbnailContent = remember(playlist) {
                playlist?.thumbnail?.let { url ->
                    adaptiveThumbnailContent(
                        isLoading = false,
                        url = url
                    )
                } ?: { }
            }

            Scaffold(
                key = "localplaylist",
                topIconButtonId = R.drawable.chevron_back,
                onTopIconButtonClick = pop,
                tabIndex = 0,
                onTabChange = { },
                tabColumnContent = {
                    tab(0, R.string.songs, R.drawable.musical_notes)
                }
            ) { currentTabIndex ->
                saveableStateHolder.SaveableStateProvider(currentTabIndex) {
                    playlist?.let {
                        when (currentTabIndex) {
                            0 -> LocalPlaylistSongs(
                                playlist = it,
                                songs = songs,
                                thumbnailContent = thumbnailContent,
                                onDelete = pop
                            )
                        }
                    }
                }
            }
        }
    }
}
