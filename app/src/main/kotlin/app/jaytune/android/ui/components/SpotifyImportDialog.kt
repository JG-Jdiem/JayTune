package app.jaytune.android.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.jaytune.android.Database
import app.jaytune.android.DatabaseInitializer
import app.jaytune.android.R
import app.jaytune.android.models.Playlist
import app.jaytune.android.models.Song
import app.jaytune.android.models.SongPlaylistMap
import app.jaytune.android.ui.components.themed.DefaultDialog
import app.jaytune.android.ui.components.themed.DialogTextButton
import app.jaytune.android.utils.semiBold
import app.jaytune.core.ui.LocalAppearance
import app.jaytune.providers.innertube.Innertube
import app.jaytune.providers.innertube.models.bodies.SearchBody
import app.jaytune.providers.innertube.requests.searchPage
import app.jaytune.providers.innertube.utils.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SpotifyImportDialog(
    onDismiss: () -> Unit,
    onImportComplete: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val (colorPalette, typography) = LocalAppearance.current

    var showGuide by remember { mutableStateOf(true) }
    var showProgress by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf(0) }
    var importTotal by remember { mutableStateOf(0) }
    var importPlaylistName by remember { mutableStateOf("") }

    val pickCsvLauncher = rememberLauncherForActivityResult(
        CsvPickerContract()
    ) { uri: Uri? ->
        uri?.let {
            val fileName = getFileName(context, it) ?: context.getString(R.string.default_imported_playlist_name)
            val playlistName = fileName.removeSuffix(".csv").removeSuffix(".CSV").replace("_", " ")
            importPlaylistName = playlistName
            importProgress = 0
            importTotal = 0
            showGuide = false
            showProgress = true

            coroutineScope.launch {
                importSpotifyPlaylist(
                    context = context,
                    uri = it,
                    playlistName = playlistName,
                    onProgress = { current, total ->
                        importProgress = current
                        importTotal = total
                    }
                )
                showProgress = false
                onImportComplete()
                onDismiss()
            }
        }
    }

    if (showGuide) {
        DefaultDialog(
            onDismiss = onDismiss,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(all = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BasicText(
                    text = stringResource(R.string.spotify_import_guide_title),
                    style = typography.m.semiBold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                BasicText(
                    text = stringResource(R.string.spotify_import_guide_description),
                    style = typography.xs,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start
                ) {
                    listOf(
                        R.string.spotify_step_1,
                        R.string.spotify_step_2,
                        R.string.spotify_step_3
                    ).forEach { stepRes ->
                        BasicText(
                            text = stringResource(stepRes),
                            style = typography.xs,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                DialogTextButton(
                    text = stringResource(R.string.spotify_open_exportify),
                    primary = true,
                    onClick = { uriHandler.openUri("https://exportify.net") }
                )
                Spacer(modifier = Modifier.height(10.dp))
                DialogTextButton(
                    text = stringResource(R.string.spotify_upload_csv),
                    primary = true,
                    onClick = {
                        pickCsvLauncher.launch(
                            arrayOf("text/csv", "text/comma-separated-values", "*/*")
                        )
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                DialogTextButton(
                    text = stringResource(R.string.spotify_close),
                    primary = false,
                    onClick = onDismiss
                )
            }
        }
    }

    if (showProgress) {
        DefaultDialog(
            onDismiss = { },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(all = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BasicText(
                    text = stringResource(R.string.spotify_importing, importPlaylistName),
                    style = typography.m.semiBold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                ImportProgressBar(
                    progress = if (importTotal > 0) importProgress.toFloat() / importTotal else 0f,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                BasicText(
                    text = "$importProgress / $importTotal ${stringResource(R.string.spotify_tracks)}",
                    style = typography.xs
                )
            }
        }
    }
}

@Composable
private fun ImportProgressBar(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val (colorPalette) = LocalAppearance.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(colorPalette.background1)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .background(colorPalette.text)
        )
    }
}

private class CsvPickerContract : androidx.activity.result.contract.ActivityResultContract<Array<String>, Uri?>() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, input)
    }
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
    }
}

private fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) result = cursor.getString(idx)
            }
        }
    }
    if (result == null) result = uri.lastPathSegment
    return result
}

private data class ExportifyTrack(
    val trackName: String,
    val artistName: String
)

private fun parseExportifyCsv(inputStream: java.io.InputStream): List<ExportifyTrack> {
    val reader = inputStream.bufferedReader()
    val lines = reader.readLines()
    if (lines.isEmpty()) return emptyList()

    val headers = parseCsvLine(lines.first())
    val trackNameIdx = headers.indexOf("Track Name")
    val artistIdx = headers.indexOf("Artist Name(s)")

    return lines.drop(1).mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val fields = parseCsvLine(line)
        if (trackNameIdx < 0 || trackNameIdx >= fields.size) return@mapNotNull null
        if (artistIdx < 0 || artistIdx >= fields.size) return@mapNotNull null
        ExportifyTrack(
            trackName = fields[trackNameIdx],
            artistName = fields[artistIdx]
        )
    }
}

private fun parseCsvLine(line: String): List<String> {
    val result = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' -> {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    sb.append('"')
                    i += 2
                    continue
                } else {
                    inQuotes = !inQuotes
                }
            }
            c == ',' && !inQuotes -> {
                result.add(sb.toString())
                sb.clear()
            }
            else -> sb.append(c)
        }
        i++
    }
    result.add(sb.toString())
    return result
}

private suspend fun importSpotifyPlaylist(
    context: Context,
    uri: Uri,
    playlistName: String,
    onProgress: (Int, Int) -> Unit
) = withContext(Dispatchers.IO) {
    val tracks = context.contentResolver.openInputStream(uri)?.use { stream ->
        parseExportifyCsv(stream)
    } ?: return@withContext

    if (tracks.isEmpty()) return@withContext

    var playlistId: Long = -1
    DatabaseInitializer.instance.runInTransaction {
        playlistId = Database.insert(Playlist(name = playlistName))
    }

    if (playlistId == -1L) return@withContext

    val maps = mutableListOf<SongPlaylistMap>()

    tracks.forEachIndexed { index, track ->
        onProgress(index, tracks.size)

        val searchQuery = "${track.trackName} ${track.artistName}"
        val result = Innertube.searchPage(
            body = SearchBody(
                query = searchQuery,
                params = Innertube.SearchFilter.Song.value
            ),
            fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
        )

        val songItem = result?.getOrNull()?.items?.firstOrNull()
        if (songItem != null) {
            val song = Song(
                id = songItem.key,
                title = songItem.info?.name.orEmpty(),
                artistsText = songItem.authors?.joinToString("") { it.name.orEmpty() },
                durationText = songItem.durationText,
                thumbnailUrl = songItem.thumbnail?.size(512),
                explicit = songItem.explicit
            )

            DatabaseInitializer.instance.runInTransaction {
                Database.insert(song)
                maps.add(
                    SongPlaylistMap(
                        playlistId = playlistId,
                        songId = song.id,
                        position = index
                    )
                )
                Unit
            }
        }
    }

    if (maps.isNotEmpty()) {
        DatabaseInitializer.instance.runInTransaction {
            Database.insertSongPlaylistMaps(maps)
            Unit
        }
    }

    onProgress(tracks.size, tracks.size)
}
