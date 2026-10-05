package com.nevoit.cirro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.nevoit.material.core.component.NavigationBar
import com.nevoit.material.core.component.NavigationBarItem
import com.nevoit.material.navigation.CrossfadeNavContainer

data class NavItem(
    val iconRes: Int,
    val label: String
)

enum class NavItems(val item: NavItem) {
    Home(NavItem(iconRes = R.drawable.home, label = "Home")),
    Explore(NavItem(iconRes = R.drawable.square_grid_2x2, label = "Explore")),
    Library(NavItem(iconRes = R.drawable.music_note_square_stack, label = "Library")),
    Search(NavItem(iconRes = R.drawable.magnifyingglass, label = "Search")),
}

enum class MainDestinations {
    Home,
    Explore,
    Library,
    Search
}

@Composable
fun BoxScope.Main() {
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        CrossfadeNavContainer(
            destination = MainDestinations.Home,
            destinations = MainDestinations.entries
        ) { destination ->
            when (destination) {
                MainDestinations.Home -> {}
                MainDestinations.Explore -> {}
                MainDestinations.Library -> {}
                MainDestinations.Search -> {}
            }
        }
        NavigationBar(
            items = NavItems.entries.map { entry ->
                val icon = painterResource(entry.item.iconRes)
                NavigationBarItem(
                    icon = icon,
                    label = entry.item.label
                )
            },
            selectedIndex = selectedIndex,
            onSelect = { selectedIndex = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp)
                .fillMaxWidth()
        )
    }
}
