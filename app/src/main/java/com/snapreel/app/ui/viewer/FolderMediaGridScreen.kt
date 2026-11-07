package com.snapreel.app.ui.viewer

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.bitmapConfig
import coil3.request.crossfade
import com.snapreel.app.data.repository.FolderLoadError
import com.snapreel.app.data.repository.displayMessage
import com.snapreel.app.ui.common.FolderAccessLost
import com.snapreel.app.ui.theme.*
import com.snapreel.app.util.thumbnail.videoThumbnailRequest

import androidx.compose.foundation.lazy.grid.rememberLazyGridState

@Composable
fun FolderMediaGridScreen(
    folderUri: Uri,
    returnedIndex: Int? = null,
    onBack: () -> Unit,
    onMediaClick: (Int, Boolean) -> Unit,
    onOpenOtherFolder: (Uri) -> Unit = {},
    viewModel: FolderGridViewModel = hiltViewModel()
) {
    FolderMediaGridScreen(
        folderUri = folderUri,
        returnedIndex = returnedIndex,
        onBack = onBack,
        onMediaClick = { index, isLandscape, _ -> onMediaClick(index, isLandscape) },
        onOpenOtherFolder = onOpenOtherFolder,
        viewModel = viewModel
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderMediaGridScreen(
    folderUri: Uri,
    returnedIndex: Int? = null,
    onBack: () -> Unit,
    onMediaClick: (Int, Boolean, Boolean) -> Unit,
    onOpenOtherFolder: (Uri) -> Unit = {},
    viewModel: FolderGridViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val gridState = rememberLazyGridState()
    val currentOnOpenOtherFolder by rememberUpdatedState(onOpenOtherFolder)

    LaunchedEffect(folderUri) {
        viewModel.loadMedia(folderUri)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is FolderEvent.OpenOtherFolder -> currentOnOpenOtherFolder(event.uri)
            }
        }
    }

    val isLandscapeMode = settings.landscapeVideoMode
    val isFavoritesTab = uiState.selectedTab == FolderTab.FAVORITES
    val displayedItems = remember(uiState.mediaItems, isLandscapeMode, isFavoritesTab, favorites) {
        MediaIndexMapping.gridDisplayedItems(
            mediaItems = uiState.mediaItems,
            isLandscapeMode = isLandscapeMode,
            favoritesOnly = isFavoritesTab,
            favorites = favorites
        )
    }
    val loadError = uiState.error

    LaunchedEffect(returnedIndex, displayedItems.size) {
        if (displayedItems.isNotEmpty()) {
            val targetIndex = returnedIndex ?: viewModel.getSavedLastIndex(folderUri)
            val scrollIndex = if (isLandscapeMode || isFavoritesTab) {
                val targetUri = uiState.mediaItems.getOrNull(targetIndex)?.uri
                val found = displayedItems.indexOfFirst { it.uri == targetUri }
                if (found >= 0) found else 0
            } else {
                targetIndex
            }
            if (scrollIndex in displayedItems.indices) {
                gridState.scrollToItem(scrollIndex)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(
                            "Folder Media", 
                            maxLines = 1, 
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = if (isLandscapeMode) "Landscape Mode (Videos Only)" else "Reels Mode (All Media)",
                            color = if (isLandscapeMode) Violet400 else Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    // Quick Toggle for Landscape Video Mode vs Reels Mode
                    IconButton(
                        onClick = { viewModel.toggleLandscapeMode() }
                    ) {
                        Icon(
                            imageVector = if (isLandscapeMode) Icons.Filled.StayCurrentLandscape else Icons.Filled.StayCurrentPortrait,
                            contentDescription = if (isLandscapeMode) "Switch to Reels Mode" else "Switch to Landscape Mode",
                            tint = if (isLandscapeMode) Violet400 else Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E1E1E)
                )
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Folder Tabs: Semua Media vs Favorit
            TabRow(
                selectedTabIndex = if (uiState.selectedTab == FolderTab.ALL) 0 else 1,
                containerColor = Color(0xFF1E1E1E),
                contentColor = Color.White
            ) {
                Tab(
                    selected = uiState.selectedTab == FolderTab.ALL,
                    onClick = { viewModel.selectTab(FolderTab.ALL) },
                    text = { Text("Semua Media", fontWeight = FontWeight.Medium) },
                    icon = { Icon(Icons.Filled.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = uiState.selectedTab == FolderTab.FAVORITES,
                    onClick = { viewModel.selectTab(FolderTab.FAVORITES) },
                    text = {
                        val favCount = remember(uiState.mediaItems, favorites) {
                            uiState.mediaItems.count { it.uri.toString() in favorites }
                        }
                        Text(if (favCount > 0) "Favorit ($favCount)" else "Favorit", fontWeight = FontWeight.Medium)
                    },
                    icon = {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = if (uiState.selectedTab == FolderTab.FAVORITES) ErrorRed else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }

            // If Favorites tab is selected and has items: Show "Putar Semua Favorit" action bar
            if (isFavoritesTab && displayedItems.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceCard)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${displayedItems.size} item favorit",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Button(
                        onClick = {
                            val fullIndex = MediaIndexMapping.gridTapToFullIndex(
                                uiState.mediaItems,
                                isLandscapeMode,
                                0,
                                favoritesOnly = true,
                                favorites = favorites
                            )
                            onMediaClick(fullIndex, isLandscapeMode, true)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Violet600),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Putar Semua Favorit", fontSize = 13.sp)
                    }
                }
            }

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Violet500)
                }
            } else if (loadError is FolderLoadError.AccessLost) {
                FolderAccessLost(
                    folderUri = folderUri,
                    onRePicked = { picked -> viewModel.onFolderRePicked(folderUri, picked) }
                )
            } else if (loadError is FolderLoadError.Failed) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text(
                            text = loadError.displayMessage,
                            color = Color.White,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(onClick = { viewModel.retry(folderUri) }) {
                            Text("Retry")
                        }
                    }
                }
            } else if (displayedItems.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(
                        items = displayedItems,
                        key = { _, item -> item.uri.toString() },
                        contentType = { _, item -> if (item.isVideo) "video" else "image" }
                    ) { index, item ->
                        val isFav = item.uri.toString() in favorites
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .background(Color.DarkGray)
                                .clickable {
                                    onMediaClick(
                                        MediaIndexMapping.gridTapToFullIndex(
                                            uiState.mediaItems,
                                            isLandscapeMode,
                                            index,
                                            favoritesOnly = isFavoritesTab,
                                            favorites = favorites
                                        ),
                                        isLandscapeMode,
                                        isFavoritesTab
                                    )
                                }
                        ) {
                            if (item.isVideo) {
                                Icon(
                                    imageVector = Icons.Outlined.Movie,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.35f),
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(32.dp)
                                )
                                val context = LocalContext.current
                                val request = remember(item.uri, item.size, item.dateModified, item.supportsThumbnail) {
                                    videoThumbnailRequest(context, item, cacheOnly = false, crossfade = true)
                                }
                                AsyncImage(
                                    model = request,
                                    contentDescription = item.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                val context = LocalContext.current
                                val imageRequest = remember(item.uri) {
                                    ImageRequest.Builder(context)
                                        .data(item.uri)
                                        .size(300, 300)
                                        .bitmapConfig(Bitmap.Config.RGB_565)
                                        .crossfade(true)
                                        .build()
                                }
                                AsyncImage(
                                    model = imageRequest,
                                    contentDescription = item.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            if (item.isVideo) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(20.dp)
                                )
                            }

                            // Favorite Heart Badge / Quick Toggle
                            IconButton(
                                onClick = { viewModel.toggleFavorite(item.uri) },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(36.dp)
                            ) {
                                Icon(
                                    imageVector = if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                    contentDescription = if (isFav) "Hapus dari Favorit" else "Tambah ke Favorit",
                                    tint = if (isFav) ErrorRed else Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            } else if (isFavoritesTab) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Icon(
                            imageVector = Icons.Filled.FavoriteBorder,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Belum ada media favorit di folder ini.",
                            color = Color.White,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Ketuk ikon hati pada media untuk menyimpannya sebagai favorit.",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(onClick = { viewModel.selectTab(FolderTab.ALL) }) {
                            Text("Lihat Semua Media")
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text(
                            text = if (isLandscapeMode) "No videos found in this folder." else "No media found in this folder.",
                            color = Color.White,
                            fontSize = 15.sp
                        )
                        if (isLandscapeMode && uiState.mediaItems.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(onClick = { viewModel.toggleLandscapeMode() }) {
                                Text("Switch to Reels Mode to view images")
                            }
                        }
                    }
                }
            }
        }
    }
}
