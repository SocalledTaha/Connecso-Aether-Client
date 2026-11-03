package com.whitedns.whiteaesther.ui

import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.whitedns.whiteaesther.R
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.InstalledApp
import com.whitedns.whiteaesther.data.InstalledApps
import com.whitedns.whiteaesther.data.SplitTunnel
import com.whitedns.whiteaesther.data.SplitTunnelMode
import com.whitedns.whiteaesther.ui.theme.AetherTheme
import com.whitedns.whiteaesther.ui.theme.AetherType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app picker.
 *
 * A lazy list rather than the scrolling column every other screen uses, and the
 * only screen that needs to be: a phone can have two hundred launchable apps,
 * and composing and measuring all of them at once is a stall the user feels on
 * every scroll. Here only the rows on screen exist.
 */
@Composable
fun SplitTunnelScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    val colors = AetherTheme.colors
    val context = LocalContext.current
    val rules = settings.splitTunnel
    val self = context.packageName
    val listing = rules.mode != SplitTunnelMode.ALL
    val appListRevision by InstalledApps.revision.collectAsState()

    // Off the main thread. Reading a label costs a package manager call each,
    // and doing that for every app during composition is what made opening this
    // screen take seconds.
    val apps by produceState(initialValue = null as List<InstalledApp>?, listing, appListRevision) {
        if (!listing) return@produceState
        value = withContext(Dispatchers.IO) {
            InstalledApps.launchable(context).filterNot { it.packageName == self }
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var showSystemApps by rememberSaveable { mutableStateOf(false) }
    val searchInteraction = remember { MutableInteractionSource() }
    val visible = remember(apps, query, showSystemApps) {
        val all = apps.orEmpty()
            .filter { showSystemApps || !it.system }
        if (query.isBlank()) all else all.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }

    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize().imePadding().testTag("split-app-list"),
        contentPadding = PaddingValues(horizontal = 18.dp),
    ) {
        item(key = "header") {
            CrumbBar(stringResource(R.string.traffic_apps), onBack = onBack)
            PageTitle(
                stringResource(R.string.which_apps_go_through),
                stringResource(R.string.leave_this_on_all_apps_unless_something),
            )

            AetherCard {
                Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SplitTunnelMode.entries.forEach { mode ->
                        OptionRow(
                            code = when (mode) {
                                SplitTunnelMode.ALL -> "1"
                                SplitTunnelMode.ONLY -> "2"
                                SplitTunnelMode.EXCEPT -> "3"
                            },
                            title = stringResource(when (mode) {
                                SplitTunnelMode.ALL -> R.string.split_all_apps_use_vpn
                                SplitTunnelMode.ONLY -> R.string.split_selected_use_vpn
                                SplitTunnelMode.EXCEPT -> R.string.split_selected_bypass_vpn
                            }),
                            subtitle = when (mode) {
                                SplitTunnelMode.ALL -> stringResource(R.string.everything_is_covered_what_most_people_want)
                                SplitTunnelMode.ONLY -> stringResource(R.string.only_what_you_pick_anything_installed_later)
                                SplitTunnelMode.EXCEPT -> stringResource(R.string.everything_but_what_you_pick_for_banking)
                            },
                            selected = rules.mode == mode,
                            onClick = {
                                onSettingsChange(
                                    settings.copy(splitTunnel = rules.copy(mode = mode)),
                                )
                            },
                        )
                    }
                }
            }
        }

        if (!listing) {
            item(key = "all-note") {
                Note(
                    stringResource(R.string.every_app_on_the_phone_goes_through),
                )
                Spacer(Modifier.height(26.dp))
            }
            return@LazyColumn
        }

        rules.validationError(self)?.let { problem ->
            item(key = "problem") {
                Spacer(Modifier.height(12.dp))
                AttentionCard(
                    tone = colors.signalFailed,
                    title = stringResource(R.string.nothing_would_be_carried),
                    body = stringResource(problem),
                )
            }
        }

        item(key = "search") {
            Spacer(Modifier.height(12.dp))
            SectionLabel(
                if (rules.mode == SplitTunnelMode.ONLY) stringResource(R.string.apps_to_carry) else stringResource(R.string.apps_to_leave_out),
            )
            Spacer(Modifier.height(4.dp))
            Text(rules.summary(), style = AetherTheme.type.Small, color = colors.text2)
            Spacer(Modifier.height(9.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .tvTextFieldSupport(searchInteraction)
                    .testTag("split-search"),
                interactionSource = searchInteraction,
                placeholder = { Text(stringResource(R.string.search_apps), style = AetherTheme.type.Body, color = colors.text3) },
                textStyle = AetherTheme.type.Body.copy(color = colors.text),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.ink1,
                    unfocusedContainerColor = colors.ink1,
                ),
            )
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                SplitAction(stringResource(R.string.split_select_all)) {
                    val next = rules.packages + visible.map { it.packageName }
                    onSettingsChange(settings.copy(splitTunnel = rules.copy(packages = next)))
                }
                SplitAction(stringResource(R.string.split_invert_selection)) {
                    val next = rules.packages.toMutableSet()
                    visible.forEach { app -> if (!next.add(app.packageName)) next.remove(app.packageName) }
                    onSettingsChange(settings.copy(splitTunnel = rules.copy(packages = next)))
                }
                SplitAction(stringResource(R.string.split_clear_selection)) {
                    onSettingsChange(settings.copy(splitTunnel = rules.copy(packages = emptySet())))
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                SplitAction(stringResource(if (showSystemApps) R.string.split_system_apps_hide else R.string.split_system_apps_show)) {
                    showSystemApps = !showSystemApps
                }
                SplitAction(stringResource(R.string.split_export_list)) {
                    runCatching {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Connecso Split Tunneling", rules.exportText()))
                        Toast.makeText(context, R.string.split_export_done, Toast.LENGTH_SHORT).show()
                    }
                }
                SplitAction(stringResource(R.string.split_import_list)) {
                    runCatching {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val clip = clipboard?.primaryClip ?: error("Clipboard is empty")
                        val imported = SplitTunnel.importText(clip.getItemAt(0).coerceToText(context).toString())
                        onSettingsChange(settings.copy(splitTunnel = imported))
                        Toast.makeText(context, R.string.split_import_done, Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, R.string.split_import_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        if (apps == null) {
            item(key = "loading") {
                Note(stringResource(R.string.reading_the_apps_on_this_phone))
            }
        } else if (visible.isEmpty()) {
            item(key = "empty") {
                Note(
                    if (apps.orEmpty().isEmpty()) {
                        stringResource(R.string.no_apps_to_list_this_device_reports)
                    } else {
                        stringResource(R.string.nothing_matches_query, query)
                    },
                )
            }
        }

        // Keyed by package so a toggle recomposes one row rather than the list.
        items(visible, key = { it.packageName }) { app ->
            AppRow(
                app = app,
                checked = app.packageName in rules.packages,
                onToggle = { on ->
                    val next = if (on) {
                        rules.packages + app.packageName
                    } else {
                        rules.packages - app.packageName
                    }
                    onSettingsChange(settings.copy(splitTunnel = rules.copy(packages = next)))
                },
            )
        }

        item(key = "footer") {
            Note(
                stringResource(R.string.whiteaesther_itself_is_never_in_this_list),
            )
            Spacer(Modifier.height(26.dp))
        }
    }
}

@Composable
private fun SplitAction(label: String, onClick: () -> Unit) {
    val colors = AetherTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.clip(RoundedCornerShape(11.dp))
            .background(colors.ink2)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(11.dp))
            .clickable(interaction, LocalIndication.current, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = AetherTheme.type.Small, color = colors.brand,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val colors = AetherTheme.colors
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(15.dp)

    // Load only rows that LazyColumn actually composes. Warming every installed
    // icon at once competed with scroll frames on phones with large app lists.
    val icon by produceState<ImageBitmap?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            InstalledApps.iconBitmap(context, app.packageName, ICON_PX)?.asImageBitmap()
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.ink2)
            .border(1.dp, if (checked) colors.brand.copy(alpha = 0.55f) else colors.lineSoft, shape)
            .tvControllerActivation { onToggle(!checked) }
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Switch,
                onValueChange = onToggle,
            )
            .controllerFocus(interaction, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("split-app-${app.packageName}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(colors.ink3)
                .border(1.dp, colors.line, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = icon
            if (bitmap != null) {
                Icon(
                    painter = remember(bitmap) { BitmapPainter(bitmap) },
                    contentDescription = null,
                    modifier = Modifier.size(27.dp),
                    tint = Color.Unspecified,
                )
            } else {
                // Its initial while the icon loads, so the row never changes
                // height and the list does not jump under the user's finger.
                Text(
                    app.label.take(1).uppercase(),
                    style = AetherTheme.type.RowTitle,
                    color = colors.text2,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = AetherTheme.type.RowTitle,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (app.system) stringResource(R.string.system_app) else app.packageName,
                style = AetherTheme.type.Small,
                color = colors.text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AetherSwitch(checked = checked, onCheckedChange = null)
    }
    Divider()
}

/** 22dp at the densest screens we support, rounded up. */
private const val ICON_PX = 96

private class BitmapPainter(private val image: ImageBitmap) : Painter() {
    override val intrinsicSize = Size(image.width.toFloat(), image.height.toFloat())

    override fun DrawScope.onDraw() {
        drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
    }
}
