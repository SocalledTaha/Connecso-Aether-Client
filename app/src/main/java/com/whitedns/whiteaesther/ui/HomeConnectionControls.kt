package com.whitedns.whiteaesther.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whitedns.whiteaesther.R
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.Carrier
import com.whitedns.whiteaesther.service.EngineStage
import com.whitedns.whiteaesther.service.EngineStatus
import com.whitedns.whiteaesther.service.TrafficSample
import com.whitedns.whiteaesther.service.formatRate
import com.whitedns.whiteaesther.ui.theme.AetherTheme

@Composable
internal fun AppSettings.methodSummary(): String = when {
    automaticCarrier -> stringResource(R.string.carrier_automatic)
    secondCarrier != null -> stringResource(
        R.string.home_method_pair, stringResource(carrier.label), stringResource(secondCarrier.label),
    )
    carrier == Carrier.AETHER && transport.endpointFamily == com.whitedns.whiteaesther.data.EndpointFamily.WARP ->
        stringResource(R.string.home_method_pair, stringResource(carrier.label), stringResource(transport.label))
    carrier == Carrier.AETHER -> stringResource(R.string.home_method_pair, stringResource(carrier.label), stringResource(R.string.protocol_masque_h2))
    else -> stringResource(carrier.label)
}

@Composable
internal fun HomeMethodMenu(
    settings: AppSettings,
    status: EngineStatus,
    onSettingsChange: (AppSettings) -> Unit,
    onGoToRoutes: () -> Unit,
) {
    val editable = status.stage == EngineStage.IDLE || status.stage == EngineStage.ERROR
    val television = LocalTvMode.current
    var chooseMethod by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(editable) { if (!editable) chooseMethod = false }
    Box {
        HomeSelectionRow(
            stringResource(R.string.home_method), settings.methodSummary(),
            "home-method", editable, forceStacked = true, onClick = {
                if (television) onGoToRoutes() else chooseMethod = true
            },
        )
        DropdownMenu(
            expanded = chooseMethod && editable,
            onDismissRequest = { chooseMethod = false },
            modifier = Modifier.heightIn(max = 520.dp),
        ) {
            Text(
                stringResource(R.string.home_method),
                style = AetherTheme.type.CardTitle,
                color = AetherTheme.colors.text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            MethodChoices(settings) {
                onSettingsChange(it)
                chooseMethod = false
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_method_settings)) },
                onClick = { chooseMethod = false; onGoToRoutes() },
            )
        }
    }
}

@Composable
internal fun MethodChoices(settings: AppSettings, onSelect: (AppSettings) -> Unit) {
    OptionRow(
        code = "AUTO", title = stringResource(R.string.carrier_automatic),
        subtitle = stringResource(R.string.home_automatic_description),
        selected = settings.automaticCarrier,
        onClick = { onSelect(settings.copy(automaticCarrier = true)) },
    )
    OptionRow(
        code = "AETH", title = stringResource(R.string.carrier_aether),
        subtitle = stringResource(R.string.carrier_aether_detail),
        selected = !settings.automaticCarrier && settings.carrier == Carrier.AETHER &&
            settings.transport.endpointFamily != com.whitedns.whiteaesther.data.EndpointFamily.WARP,
        onClick = {
            onSelect(settings.copy(
                automaticCarrier = false,
                carrier = Carrier.AETHER,
                secondCarrier = null,
                transport = com.whitedns.whiteaesther.data.TunnelProtocol.AUTO,
            ))
        },
    )
    OptionRow(
        code = "WARP", title = stringResource(R.string.home_cloudflare_warp),
        subtitle = stringResource(R.string.home_cloudflare_warp_detail),
        selected = !settings.automaticCarrier && settings.carrier == Carrier.AETHER &&
            settings.transport == com.whitedns.whiteaesther.data.TunnelProtocol.WIREGUARD,
        onClick = {
            onSelect(settings.copy(
                automaticCarrier = false,
                carrier = Carrier.AETHER,
                secondCarrier = null,
                transport = com.whitedns.whiteaesther.data.TunnelProtocol.WIREGUARD,
            ))
        },
    )
    Carrier.entries.forEach { carrier ->
        if (carrier == Carrier.AETHER) return@forEach
        OptionRow(
            code = carrier.wireName.take(3).uppercase(),
            title = stringResource(carrier.label),
            subtitle = stringResource(when (carrier) {
                Carrier.AETHER -> R.string.carrier_aether_detail
                Carrier.PSIPHON -> R.string.carrier_psiphon_detail
                Carrier.TOR -> R.string.carrier_tor_detail
            }),
            selected = !settings.automaticCarrier && settings.carrier == carrier && settings.secondCarrier == null,
            onClick = { onSelect(settings.copy(automaticCarrier = false, carrier = carrier, secondCarrier = null)) },
        )
    }
    if (!settings.automaticCarrier && settings.secondCarrier != null) {
        OptionRow(
            code = null, title = stringResource(R.string.home_multi_hop),
            subtitle = settings.methodSummary(), selected = true,
            onClick = { onSelect(settings) },
        )
    }
}

@Composable
internal fun HomeSelectionRow(
    title: String,
    value: String,
    tag: String,
    enabled: Boolean = true,
    forceStacked: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = AetherTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(8.dp)
    val configuration = LocalConfiguration.current
    val stacked = forceStacked || configuration.fontScale > 1.3f || configuration.screenWidthDp < 360
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .tvControllerActivation(enabled, onClick)
            .clickable(interaction, LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .controllerFocus(interaction, shape, enabled).testTag(tag)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (stacked) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = AetherTheme.type.Small, color = colors.text2)
                Text(value, style = AetherTheme.type.RowTitle, color = if (enabled) colors.text else colors.text2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text(title, style = AetherTheme.type.Small, color = colors.text2, modifier = Modifier.weight(0.9f))
            Text(
                value, style = AetherTheme.type.RowTitle,
                color = if (enabled) colors.text else colors.text2,
                modifier = Modifier.weight(1.1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(AetherIcons.Chevron, null, Modifier.size(16.dp), colors.text2)
    }
}

@Composable
internal fun HomeTrafficStrip(traffic: TrafficSample, active: Boolean) {
    val colors = AetherTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("home-traffic-strip"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(
            R.string.download to traffic.downloadPerSecond,
            R.string.upload to traffic.uploadPerSecond,
        ).forEach { (label, rate) ->
            Column(
                Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(stringResource(label), style = AetherTheme.type.Small, color = colors.text2)
                Text(
                    if (active && traffic.supported) formatRate(rate) else "—",
                    style = AetherTheme.type.Data, color = colors.text,
                )
            }
        }
    }
}
