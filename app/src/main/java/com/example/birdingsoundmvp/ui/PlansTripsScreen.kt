package com.example.birdingsoundmvp.ui
import com.example.birdingsoundmvp.ui.OwlettButton as Button
import com.example.birdingsoundmvp.ui.OwlettOutlinedButton as OutlinedButton

import com.example.birdingsoundmvp.i18n.AppText

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.ui.state.ToggleableState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.birdingsoundmvp.planning.EbirdRegionOption
import com.example.birdingsoundmvp.planning.ManagedTripItem
import com.example.birdingsoundmvp.planning.PlanAnalysisTaskUiState
import com.example.birdingsoundmvp.planning.PlanDetailData
import com.example.birdingsoundmvp.planning.PlanEditorUiState
import com.example.birdingsoundmvp.planning.PlanExpectedSpecies
import com.example.birdingsoundmvp.planning.PlanRareObservation
import com.example.birdingsoundmvp.planning.PlanSettlementCategory
import com.example.birdingsoundmvp.planning.PlanSettlementSpecies
import com.example.birdingsoundmvp.planning.PlanSpeciesSort
import com.example.birdingsoundmvp.planning.PlanSpeciesStat
import com.example.birdingsoundmvp.planning.PlanSummaryItem
import com.example.birdingsoundmvp.planning.PlansTripsRoute
import com.example.birdingsoundmvp.planning.PlansTripsSection
import com.example.birdingsoundmvp.planning.PlansTripsUiState
import com.example.birdingsoundmvp.planning.PlansTripsViewModel
import com.example.birdingsoundmvp.planning.speciesIdentityAliases
import com.example.birdingsoundmvp.trip.TripHistoryItem
import java.text.DateFormat
import java.util.Date

@Composable
fun PlansTripsScreen(
    viewModel: PlansTripsViewModel,
    onReviewTrip: (String) -> Unit,
    onOpenSpecies: (String, String) -> Unit = { _, _ -> }
) {
    val state by viewModel.state.collectAsState()
    CompositionLocalProvider(LocalOpenSpecies provides onOpenSpecies) {
    when (val route = state.route) {
        PlansTripsRoute.Overview -> PlansTripsOverview(state, viewModel, onReviewTrip)
        is PlansTripsRoute.PlanDetail -> PlanDetailScreen(state, route.planId, viewModel)
        is PlansTripsRoute.PlanEditor -> PlanEditorScreen(state, viewModel)
    }
    if (state.tripPicker.isVisible) {
        TripPickerDialog(state, viewModel)
    }
    if (state.showTrash) {
        AlertDialog(onDismissRequest = { viewModel.showTrash(false) }, title = { Text("计划回收站") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    if (state.trashedPlans.isEmpty()) item { Text("回收站为空") }
                    items(state.trashedPlans, key = { it.id }) { plan ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(plan.name, style = MaterialTheme.typography.bodyLarge)
                                Text("${plan.plannedDate} · ${plan.hotspotName}", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { viewModel.restorePlan(plan.id) }) {
                                Icon(Icons.Default.RestoreFromTrash, contentDescription = "恢复 ${plan.name}")
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { viewModel.showTrash(false) }) { Text("关闭") } })
    }
}
}

@Composable
private fun PlansTripsOverview(
    state: PlansTripsUiState,
    viewModel: PlansTripsViewModel,
    onReviewTrip: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                AppText.get("Plans & Trips"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = viewModel::refresh) {
                Icon(Icons.Default.Refresh, contentDescription = AppText.get("Refresh"))
            }
            if (state.section == PlansTripsSection.PLANS) {
                IconButton(onClick = { viewModel.showTrash(true) }) {
                    Icon(Icons.Default.RestoreFromTrash, contentDescription = "计划回收站")
                }
                IconButton(onClick = viewModel::openNewPlan) {
                    Icon(Icons.Default.Add, contentDescription = AppText.get("Create plan"))
                }
            }
        }
        ParallelTabs(
            selected = state.section,
            onSelected = viewModel::selectSection
        )
        NoticeLine(state.error, state.message, viewModel::clearNotice)
        when (state.section) {
            PlansTripsSection.PLANS -> PlansList(state, viewModel)
            PlansTripsSection.TRIPS -> TripsList(state, viewModel, onReviewTrip)
        }
    }
}

@Composable
private fun ParallelTabs(
    selected: PlansTripsSection,
    onSelected: (PlansTripsSection) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        PlansTripsSection.entries.forEach { section ->
            val active = selected == section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelected(section) }
                    .padding(top = 10.dp)
            ) {
                Text(
                    if (section == PlansTripsSection.PLANS) AppText.get("Plans") else AppText.get("Trips"),
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                )
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun PlansList(state: PlansTripsUiState, viewModel: PlansTripsViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (state.isLoading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        if (state.data.plans.isEmpty() && !state.isLoading) {
            item {
                EmptyState(
                    title = AppText.get("No plans yet"),
                    text = AppText.get("Create a plan, bind one eBird hotspot, then organize recent bird activity."),
                    action = AppText.get("Create plan"),
                    onAction = viewModel::openNewPlan
                )
            }
        }
        items(state.data.plans, key = { it.plan.id }) { item ->
            PlanRow(item, state.analysisTasks[item.plan.id]) { viewModel.openPlan(item.plan.id) }
        }
    }
}

@Composable
private fun PlanRow(
    item: PlanSummaryItem,
    analysis: PlanAnalysisTaskUiState?,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.plan.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(item.plan.plannedDate, style = MaterialTheme.typography.labelMedium)
            }
            Text(
                item.plan.hotspotName.ifBlank { AppText.get("Hotspot must be reselected") },
                style = MaterialTheme.typography.bodySmall,
                color = if (item.plan.needsHotspot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                AppText.format("{0} expected  ·  {1} linked trip{2}", item.expectedSpeciesCount, item.linkedTripCount, if (item.linkedTripCount == 1) "" else "s"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when {
                analysis?.isRunning == true -> {
                    LinearProgressIndicator(progress = { analysis.progress }, modifier = Modifier.fillMaxWidth())
                    Text(analysis.message, style = MaterialTheme.typography.labelSmall)
                }
                item.settlement?.isStale == true -> StatusText(AppText.get("Settlement needs refresh"), MaterialTheme.colorScheme.error)
                item.settlement != null -> StatusText(AppText.get("Settlement ready"), MaterialTheme.colorScheme.primary)
                item.plan.hasAnalysis -> StatusText(AppText.get("Bird activity organized"), MaterialTheme.colorScheme.primary)
                else -> StatusText(AppText.get("Not organized"), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TripsList(
    state: PlansTripsUiState,
    viewModel: PlansTripsViewModel,
    onReviewTrip: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var deleteTrip by remember { mutableStateOf<TripHistoryItem?>(null) }
    val filtered = state.data.trips.filter { managed ->
        query.isBlank() || managed.trip.tripId.contains(query, ignoreCase = true) ||
            managed.linkedPlans.any { it.name.contains(query, ignoreCase = true) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column {
                Text(AppText.format("Storage {0}", formatBytes(state.data.storageBytes)), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.deleteOldAudio(7) }, modifier = Modifier.weight(1f)) {
                        Text(AppText.get("Clear >7 days"))
                    }
                    OutlinedButton(onClick = { viewModel.deleteOldAudio(30) }, modifier = Modifier.weight(1f)) {
                        Text(AppText.get("Clear >30 days"))
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(AppText.get("Search trips or linked plans")) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (state.isLoading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        if (filtered.isEmpty() && !state.isLoading) {
            item { EmptyState(AppText.get("No trips found"), AppText.get("Completed recordings appear here.")) }
        }
        items(filtered, key = { it.trip.tripId }) { managed ->
            ManagedTripRow(
                item = managed,
                onReview = { onReviewTrip(managed.trip.tripId) },
                onDeleteAudio = { viewModel.deleteTripAudio(managed.trip.tripId) },
                onDelete = { deleteTrip = managed.trip }
            )
        }
    }
    deleteTrip?.let { trip ->
        ConfirmDialog(
            title = AppText.get("Delete trip?"),
            text = AppText.format("The recording, detections and all Plan links for {0} will be removed.", trip.tripId),
            confirmLabel = AppText.get("Delete"),
            onDismiss = { deleteTrip = null },
            onConfirm = {
                viewModel.deleteTrip(trip.tripId)
                deleteTrip = null
            }
        )
    }
}

@Composable
private fun ManagedTripRow(
    item: ManagedTripItem,
    onReview: () -> Unit,
    onDeleteAudio: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(formatDateTime(item.trip.startedAtMs), fontWeight = FontWeight.SemiBold)
                    Text(item.trip.tripId, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(formatDuration(item.trip.durationMs), style = MaterialTheme.typography.labelLarge)
            }
            Text(
                AppText.format("{0} detections · {1} species · ", item.trip.detectionCount, item.trip.uniqueSpeciesCount) +
                    if (item.trip.audioBytes > 0) formatBytes(item.trip.audioBytes) else AppText.get("audio removed"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (item.linkedPlans.isNotEmpty()) {
                Text(
                    AppText.format("Plans: {0}", item.linkedPlans.joinToString { it.name }),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onReview, enabled = item.trip.isCompleted, modifier = Modifier.weight(1f)) {
                    Text(AppText.get("Review"))
                }
                TextButton(onClick = onDeleteAudio, enabled = item.trip.audioBytes > 0) { Text(AppText.get("Delete audio")) }
                IconButton(onClick = onDelete, enabled = item.trip.isCompleted) {
                    Icon(Icons.Default.Delete, contentDescription = AppText.get("Delete trip"))
                }
            }
        }
    }
}

@Composable
private fun PlanEditorScreen(state: PlansTripsUiState, viewModel: PlansTripsViewModel) {
    val editor = state.editor
    var showCountries by remember { mutableStateOf(false) }
    var showRegions by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    val sources = viewModel.observationSources.all()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SimpleHeader(
            title = if (editor.planId == null) AppText.get("New plan") else AppText.get("Edit plan"),
            onBack = viewModel::navigateBack
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (sources.size > 1) item {
                Box {
                    OutlinedButton(onClick = { showSources = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("数据来源：${sources.firstOrNull { it.id == editor.sourceId }?.displayName ?: editor.sourceId}", Modifier.weight(1f))
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = showSources, onDismissRequest = { showSources = false }) {
                        sources.forEach { source -> DropdownMenuItem(text = { Text(source.displayName) }, onClick = {
                            viewModel.selectSource(source.id); showSources = false
                        }) }
                    }
                }
            }
            item { SourceQueryStatus(viewModel.observationSources.find(editor.sourceId)) }
            if (viewModel.observationSources.find(editor.sourceId)?.capabilities?.supportsSearchModes == true) item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("exact" to "精确", "fuzzy" to "模糊").forEachIndexed { index, (mode, label) ->
                        SegmentedButton(selected = editor.searchMode == mode, onClick = { viewModel.updateSearchMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label) }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = viewModel::updateEditorName,
                    label = { Text(AppText.get("Plan name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = editor.plannedDate,
                    onValueChange = viewModel::updateEditorDate,
                    label = { Text(AppText.get("Date (YYYY-MM-DD)")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Column {
                    Text(AppText.get("Country"), style = MaterialTheme.typography.labelLarge)
                    Box {
                        OutlinedButton(onClick = { showCountries = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(editor.countryName, modifier = Modifier.weight(1f))
                            Text(editor.countryCode, style = MaterialTheme.typography.labelSmall)
                        }
                        DropdownMenu(expanded = showCountries, onDismissRequest = { showCountries = false }) {
                            state.countries.forEach { country ->
                                DropdownMenuItem(
                                    text = { Text(country.name) },
                                    trailingIcon = { Text(country.code, style = MaterialTheme.typography.labelSmall) },
                                    onClick = {
                                        viewModel.selectCountry(country)
                                        showCountries = false
                                    }
                                )
                            }
                        }
                    }
                    if (state.countriesLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    state.countryError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item {
                Column {
                    Text(AppText.get("State / province"), style = MaterialTheme.typography.labelLarge)
                    Box {
                        OutlinedButton(onClick = { showRegions = true }, enabled = !state.regionsLoading && state.regions.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Text(if (editor.regionCode == editor.countryCode) {
                                if (editor.sourceId == "ebird") "全国（可选择省级地区）" else "请选择省级地区"
                            } else editor.regionName, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = showRegions, onDismissRequest = { showRegions = false }) {
                            state.regions.forEach { region ->
                                DropdownMenuItem(
                                    text = { Text(if (region.code == editor.countryCode) "全国" else region.name) },
                                    trailingIcon = { Text(region.code, style = MaterialTheme.typography.labelSmall) },
                                    onClick = {
                                        viewModel.selectRegion(region)
                                        showRegions = false
                                    }
                                )
                            }
                        }
                    }
                    if (state.regionsLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    state.regionError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = viewModel::reloadRegions, enabled = !state.countriesLoading && !state.regionsLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text("重新加载地区")
                    }
                }
            }
            item {
                Column {
                    Text("${sources.firstOrNull { it.id == editor.sourceId }?.displayName ?: editor.sourceId} 鸟点", style = MaterialTheme.typography.labelLarge)
                    if (sources.firstOrNull { it.id == editor.sourceId }?.capabilities?.usesAppForeground == true) {
                        OutlinedTextField(value = editor.searchStartDate, onValueChange = viewModel::updateSearchStart,
                            label = { Text("查询开始日期 YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = editor.searchEndDate, onValueChange = viewModel::updateSearchEnd,
                            label = { Text("查询结束日期 YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editor.hotspotKeyword,
                            onValueChange = viewModel::updateEditorKeyword,
                            label = { Text(AppText.get("Name or keyword")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = if (editor.isSearching) viewModel::cancelLocationSearch else viewModel::searchHotspots) {
                            Icon(if (editor.isSearching) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = if (editor.isSearching) "取消地点查询" else AppText.get("Search hotspots"))
                        }
                    }
                    if (editor.isSearching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    editor.selectedHotspot?.let { selected ->
                        Text(AppText.format("Selected: {0}", selected.name), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (editor.searchMode == "fuzzy" && editor.hotspotResults.size > 1) item {
                Text("找到 ${editor.hotspotResults.size} 个不同鸟点，当前纳入 ${editor.selectedLocationIds.size} 个", style = MaterialTheme.typography.bodyMedium)
            }
            items(editor.hotspotResults, key = { it.locationId }) { hotspot ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.selectHotspot(hotspot) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (editor.searchMode == "fuzzy" && viewModel.observationSources.find(editor.sourceId)?.capabilities?.supportsSearchModes == true) Checkbox(
                        checked = hotspot.locationId in editor.selectedLocationIds,
                        onCheckedChange = { viewModel.selectHotspot(hotspot) }
                    ) else RadioButton(
                        selected = editor.selectedHotspot?.locationId == hotspot.locationId,
                        onClick = { viewModel.selectHotspot(hotspot) }
                    )
                    Column(Modifier.weight(1f)) {
                        Text(hotspot.name)
                        Text(hotspot.locationId, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            editor.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                Button(
                    onClick = viewModel::saveEditor,
                    enabled = !editor.isSaving && !editor.isSearching && editor.selectedHotspot != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (editor.isSaving) AppText.get("Saving") else AppText.get("Save plan"))
                }
            }
        }
    }
}

@Composable
private fun PlanDetailScreen(
    state: PlansTripsUiState,
    planId: Long,
    viewModel: PlansTripsViewModel
) {
    val detail = state.planDetail
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SimpleHeader(
            title = detail?.plan?.name ?: AppText.get("Plan"),
            onBack = viewModel::navigateBack,
            actions = {
                IconButton(onClick = { viewModel.editPlan(planId) }, enabled = detail != null) {
                    Icon(Icons.Default.Edit, contentDescription = AppText.get("Edit plan"))
                }
                IconButton(onClick = { confirmDelete = true }, enabled = detail != null) {
                    Icon(Icons.Default.Delete, contentDescription = AppText.get("Delete plan"))
                }
            }
        )
        if (state.isLoading && detail == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else if (detail != null) {
            PlanDetailContent(state, detail, remember(viewModel) { PlanDetailActions.from(viewModel) },
                viewModel.observationSources.find(detail.plan.sourceId))
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "移入回收站？",
            text = "30天内可恢复。关联行程和录音始终保留。",
            confirmLabel = "移入回收站",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                viewModel.deletePlan(planId)
                confirmDelete = false
            }
        )
    }
}

@Composable
internal fun PlanDetailContent(
    state: PlansTripsUiState,
    detail: PlanDetailData,
    actions: PlanDetailActions,
    source: com.example.birdingsoundmvp.planning.BirdObservationSource?
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val plan = detail.plan
    val task = state.analysisTasks[plan.id]
    val expectedAliases = detail.expectedSpecies.flatMap { expected ->
        speciesIdentityAliases(
            expected.speciesKey,
            expected.speciesCode,
            expected.scientificName,
            expected.commonName
        )
    }.toSet()
    val sortedStats = when (state.speciesSort) {
        PlanSpeciesSort.COMBINED -> detail.speciesStats.sortedByDescending { it.combinedFrequency }
        PlanSpeciesSort.HISTORICAL -> detail.speciesStats.sortedByDescending { it.historicalFrequency ?: -1f }
        PlanSpeciesSort.CURRENT -> detail.speciesStats.sortedByDescending { it.currentFrequency ?: -1f }
    }
    var manualName by remember(plan.id) { mutableStateOf("") }
    var possibleSpeciesExpanded by remember(plan.id) { mutableStateOf(true) }
    var rareSpeciesExpanded by remember(plan.id) { mutableStateOf(true) }
    var expectedSpeciesExpanded by remember(plan.id) { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(plan.plannedDate, style = MaterialTheme.typography.bodyLarge)
                Text("${plan.hotspotName.ifBlank { AppText.get("Must be reselected") }} · ${plan.regionName.ifBlank { plan.regionCode }}",
                    style = MaterialTheme.typography.bodyMedium)
                Text(source?.displayName ?: "不支持的来源：${plan.sourceId}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val places = runCatching { com.google.gson.JsonParser.parseString(plan.sourceLocationJson).asJsonObject
                    .getAsJsonArray("places")?.map { it.asString }.orEmpty() }.getOrDefault(emptyList())
                places.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (plan.needsHotspot) {
                    Text("请重新选择具体鸟点", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { actions.analyzePlan(plan.id) },
                    enabled = task?.isRunning != true && !plan.needsHotspot && source != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("整理近期鸟况")
                }
                source?.takeIf { it.capabilities.usesAppForeground }?.let { source ->
                    SourceQueryStatus(source)
                    TextButton(enabled = task?.isRunning != true, onClick = { source.refreshCache(); actions.analyzePlan(plan.id) }) { Text("强制刷新") }
                }
                if (task?.isRunning == true) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            AppText.format("{0} · {1}/{2} dates", task.message, task.completedDays, task.totalDays),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { actions.cancelAnalysis(plan.id) }) { Text(AppText.get("Cancel")) }
                    }
                }
                task?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                task?.warning?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
                if (plan.hasAnalysis) {
                    var showCoverage by remember(plan.id) { mutableStateOf(false) }
                    val requested = plan.historicalRequestedDays + plan.currentRequestedDays
                    val success = plan.historicalSuccessfulDays + plan.currentSuccessfulDays
                    Text("更新于 ${formatDateTime(plan.analysisGeneratedAtMs!!)} · 覆盖 ${if (requested == 0) 0 else success * 100 / requested}%",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "查看样本覆盖详情") { showCoverage = !showCoverage })
                    if (showCoverage) {
                    Text(
                        AppText.format("Previous years: {0}/{1} dates, ", plan.historicalSuccessfulDays, plan.historicalRequestedDays) +
                            AppText.format("{0} reporting days", plan.historicalActiveDays),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        if (plan.currentRequestedDays == 0) AppText.get("Target year: no data available yet") else
                            AppText.format("Target year: {0}/{1} dates, ", plan.currentSuccessfulDays, plan.currentRequestedDays) +
                                AppText.format("{0} reporting days", plan.currentActiveDays),
                        style = MaterialTheme.typography.bodySmall
                    )
                    }
                }
            }
        }
        if (detail.speciesStats.isNotEmpty()) {
            item(key = "possible-species-header") {
                CollapsibleListSection(
                    title = AppText.get("Possible species"),
                    itemCount = detail.speciesStats.size,
                    expanded = possibleSpeciesExpanded,
                    onToggle = { possibleSpeciesExpanded = !possibleSpeciesExpanded }
                ) {
                    SortTabs(state.speciesSort, actions.setSpeciesSort)
                    val selectedCount = detail.speciesStats.count {
                        speciesIdentityAliases(it.speciesKey, it.speciesCode, it.scientificName, it.commonName).any(expectedAliases::contains)
                    }
                    SpeciesSelectAll(selectedCount, detail.speciesStats.size) { actions.setAllExpected(false, it) }
                    Spacer(Modifier.height(8.dp))
                    SpeciesTableHeader()
                }
            }
            if (possibleSpeciesExpanded) {
                items(sortedStats, key = { possibleSpeciesItemKey(it.speciesKey) }) { stat ->
                    val selected = speciesIdentityAliases(
                        stat.speciesKey,
                        stat.speciesCode,
                        stat.scientificName,
                        stat.commonName
                    ).any(expectedAliases::contains)
                    SpeciesStatRow(stat, selected) { isSelected ->
                        actions.toggleExpected(stat, isSelected)
                    }
                }
            }
        }
        if (detail.rareObservations.isNotEmpty()) {
            item(key = "rare-species-header") {
                CollapsibleListSection(
                    title = if (source?.capabilities?.supportsNotable == true) AppText.get("Recent notable species") else "近期记录",
                    itemCount = detail.rareObservations.size,
                    expanded = rareSpeciesExpanded,
                    onToggle = { rareSpeciesExpanded = !rareSpeciesExpanded }
                ) {
                    val selectedCount = detail.rareObservations.count {
                        speciesIdentityAliases(it.speciesKey, it.speciesCode, it.scientificName, it.commonName).any(expectedAliases::contains)
                    }
                    SpeciesSelectAll(selectedCount, detail.rareObservations.size) { actions.setAllExpected(true, it) }
                }
            }
            if (rareSpeciesExpanded) {
                items(detail.rareObservations, key = { rareSpeciesItemKey(it.speciesKey) }) { rare ->
                    val selected = speciesIdentityAliases(
                        rare.speciesKey,
                        rare.speciesCode,
                        rare.scientificName,
                        rare.commonName
                    ).any(expectedAliases::contains)
                    RareSpeciesRow(rare, selected) { isSelected ->
                        actions.toggleExpected(rare, isSelected)
                    }
                    if (source?.capabilities?.supportsNotable != true) {
                        Text(com.example.birdingsoundmvp.planning.RecentObservationLabel.label(plan.historicalActiveDays,
                            detail.speciesStats.firstOrNull { it.speciesKey == rare.speciesKey }?.historicalFrequency),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                    }
                }
            }
        }
        item(key = "expected-species-section") {
            CollapsibleListSection(
                title = AppText.get("Expected species"),
                itemCount = detail.expectedSpecies.size,
                expanded = expectedSpeciesExpanded,
                onToggle = { expectedSpeciesExpanded = !expectedSpeciesExpanded }
            ) {
                Text(
                    AppText.format("{0} selected. The first analysis selects every possible species; later refreshes preserve your choices.", detail.expectedSpecies.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (detail.expectedSpecies.isEmpty()) Text(AppText.get("No expected species selected."))
                detail.expectedSpecies.forEach { expected ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { actions.removeExpected(expected) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = true,
                            onCheckedChange = { selected -> if (!selected) actions.removeExpected(expected) }
                        )
                        BirdThumbnail(expected.scientificName, expected.commonName)
                        Text(expected.displayName, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(start = 10.dp))
                    }
                }
            }
        }
        item {
            DetailSection(AppText.get("Linked trips")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        AppText.format("{0} completed trip{1}", detail.linkedTrips.size, if (detail.linkedTrips.size == 1) "" else "s"),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = actions.showTripPicker) {
                        Icon(Icons.Default.Link, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(AppText.get("Link trips"))
                    }
                }
                detail.linkedTrips.forEach { trip ->
                    Text("${formatDateTime(trip.startedAtMs)} · ${formatDuration(trip.durationMs)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            DetailSection(AppText.get("Trip settlement")) {
                if (detail.settlement?.isStale == true) {
                    Text(AppText.get("Inputs changed. The saved result is shown below until you settle again."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                }
                Button(
                    onClick = { actions.settlePlan(plan.id) },
                    enabled = !state.isSettling,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.isSettling) AppText.get("Settling") else if (detail.settlement == null) AppText.get("Settle trip") else AppText.get("Settle again"))
                }
                if (detail.settlement != null) {
                    Spacer(Modifier.height(12.dp))
                    SettlementSummary(detail.settlementSpecies, actions)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = manualName,
                        onValueChange = { manualName = it },
                        label = { Text(AppText.get("Seen but not recognized")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            actions.addManualObservation(plan.id, manualName)
                            manualName = ""
                        },
                        enabled = manualName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    ) { Text(AppText.get("Add observed species")) }
                    if (detail.settlementOverrides.isNotEmpty()) {
                        TextButton(onClick = { actions.resetSettlementOverrides(plan.id) }) {
                            Text(AppText.get("Reset manual corrections"))
                        }
                    }
                }
            }
        }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
        state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp)) } }
    }
}

@Composable
internal fun SourceQueryStatus(source: com.example.birdingsoundmvp.planning.BirdObservationSource?) {
    if (source == null || !source.capabilities.usesAppForeground) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val warning = source.largeQueryWarning?.collectAsState()?.value
    if (warning != null) {
        Text(warning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = source::narrowQuery, modifier = Modifier.weight(1f)) { Text("先缩小范围") }
            TextButton(onClick = source::continueQuery, modifier = Modifier.weight(1f)) { Text("仍然继续读取") }
        }
    }
    source.progressMessage?.let { progress ->
        val message by progress.collectAsState()
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(Modifier.fillMaxWidth()) {
        TextButton(onClick = {
            var current = context
            while (current is android.content.ContextWrapper && current !is android.app.Activity) current = current.baseContext
            (current as? android.app.Activity)?.let(source::openPage)
        }, modifier = Modifier.weight(1f)) { Text("查看查询网页") }
        if (warning == null) TextButton(onClick = source::continueQuery, modifier = Modifier.weight(1f)) { Text("继续读取") }
    }
}

@Composable
private fun SpeciesTableHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(48.dp))
        Text(AppText.get("Species"), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        Text("往年", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        Text("当年", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
internal fun SpeciesStatRow(stat: PlanSpeciesStat, selected: Boolean, onSelected: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onSelected(!selected) }.padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(selected, onCheckedChange = onSelected)
        BirdThumbnail(stat.scientificName, stat.commonName)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(stat.displayName, style = MaterialTheme.typography.titleSmall)
            if (stat.commonName.isNotBlank()) {
                Text(stat.commonName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(formatFrequency(stat.historicalFrequency), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        Text(formatFrequency(stat.currentFrequency), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
    HorizontalDivider(modifier = Modifier.padding(start = 60.dp))
}

@Composable
internal fun RareSpeciesRow(rare: PlanRareObservation, selected: Boolean, onSelected: (Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable { onSelected(!selected) }.padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(selected, onCheckedChange = onSelected)
        BirdThumbnail(rare.scientificName, rare.commonName)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(rare.displayName, fontWeight = FontWeight.Medium)
            Text(
                "${rare.observedAt} · ${rare.locationName.ifBlank { rare.locationId }}" +
                    rare.count?.let { " · $it" }.orEmpty() +
                    if (rare.provisional) AppText.get(" · provisional") else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        rare.sourceUrl?.takeIf { android.net.Uri.parse(it).scheme == "https" }?.let { url ->
            IconButton(onClick = { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } }) {
                Icon(Icons.Default.OpenInNew, contentDescription = "查看原报告")
            }
        }
    }
}

@Composable
internal fun SortTabs(selected: PlanSpeciesSort, onSelected: (PlanSpeciesSort) -> Unit) {
    val choices = listOf(
            PlanSpeciesSort.COMBINED to "综合",
            PlanSpeciesSort.HISTORICAL to "往年",
            PlanSpeciesSort.CURRENT to "计划年"
        )
    TabRow(selectedTabIndex = choices.indexOfFirst { it.first == selected }, containerColor = MaterialTheme.colorScheme.surface) {
        choices.forEach { (sort, label) ->
            Tab(selected = selected == sort, onClick = { onSelected(sort) }, text = { Text(label) })
        }
    }
}

@Composable
private fun SettlementSummary(items: List<PlanSettlementSpecies>, viewModel: PlanDetailActions) {
    PlanSettlementCategory.entries.forEach { category ->
        val categoryItems = items.filter { it.category == category }
        val label = when (category) {
            PlanSettlementCategory.HIT -> AppText.get("Recorded as expected")
            PlanSettlementCategory.MISSED -> AppText.get("Expected but not recorded")
            PlanSettlementCategory.EXTRA -> AppText.get("Recorded beyond expectations")
        }
        Text("$label (${categoryItems.size})", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        if (categoryItems.isEmpty()) Text(AppText.get("None"), style = MaterialTheme.typography.bodySmall)
        categoryItems.forEach { item -> SettlementSpeciesRow(item, viewModel) }
    }
}

@Composable
private fun SettlementSpeciesRow(item: PlanSettlementSpecies, viewModel: PlanDetailActions) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BirdThumbnail(item.scientificName, item.commonName)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(item.displayName, style = MaterialTheme.typography.bodyMedium)
            val meta = buildList {
                if (item.maxConfidence > 0f) add(AppText.format("{0}% confidence", (item.maxConfidence * 100).toInt()))
                if (item.tripCount > 0) add(AppText.format("{0} trip{1}", item.tripCount, if (item.tripCount == 1) "" else "s"))
                if (item.detectionSources.isNotEmpty()) add(item.detectionSources.sorted().joinToString("/"))
                if (item.origin == "manual") add(AppText.get("手动观察"))
            }.joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            item.origin == "manual" -> IconButton(onClick = { viewModel.removeManualObservation(item) }) {
                Icon(Icons.Default.Close, contentDescription = AppText.get("Remove manual observation"))
            }
            item.origin.contains("app") -> IconButton(onClick = { viewModel.excludeSettlementSpecies(item) }) {
                Icon(Icons.Default.Close, contentDescription = AppText.get("Mark incorrect recognition"))
            }
        }
    }
}

@Composable
private fun TripPickerDialog(state: PlansTripsUiState, viewModel: PlansTripsViewModel) {
    val picker = state.tripPicker
    val candidates = state.data.trips.filter { managed ->
        managed.trip.isCompleted && (
            picker.query.isBlank() || managed.trip.tripId.contains(picker.query, ignoreCase = true) ||
                formatDateTime(managed.trip.startedAtMs).contains(picker.query, ignoreCase = true)
            )
    }
    val planId = state.planDetail?.plan?.id ?: return
    AlertDialog(
        onDismissRequest = viewModel::dismissTripPicker,
        title = { Text(AppText.get("Link completed trips")) },
        text = {
            Column {
                OutlinedTextField(
                    value = picker.query,
                    onValueChange = viewModel::updateTripPickerQuery,
                    label = { Text(AppText.get("Search")) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(320.dp)) {
                    if (candidates.isEmpty()) item { Text(AppText.get("No completed trips found.")) }
                    items(candidates, key = { it.trip.tripId }) { managed ->
                        val selected = managed.trip.tripId in picker.selectedTripIds
                        Row(
                            Modifier.fillMaxWidth().clickable { viewModel.toggleTripPickerSelection(managed.trip.tripId) }.padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(selected, onCheckedChange = { viewModel.toggleTripPickerSelection(managed.trip.tripId) })
                            Column {
                                Text(formatDateTime(managed.trip.startedAtMs))
                                Text(
                                    AppText.format("{0} · {1} species", formatDuration(managed.trip.durationMs), managed.trip.uniqueSpeciesCount),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.saveTripLinks(planId) }) { Text(AppText.get("Save links")) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissTripPicker) { Text(AppText.get("Cancel")) }
        }
    )
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        content()
    }
    HorizontalDivider()
}

@Composable
private fun CollapsibleListSection(
    title: String,
    itemCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit = {}
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                itemCount.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) AppText.format("Collapse {0}", title) else AppText.format("Expand {0}", title)
                )
            }
        }
        if (expanded) content()
    }
    HorizontalDivider()
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
        Text(value, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun SimpleHeader(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = AppText.get("Back")) }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
    HorizontalDivider()
}

@Composable
private fun EmptyState(
    title: String,
    text: String,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        if (action != null && onAction != null) Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun NoticeLine(error: String?, message: String?, onDismiss: () -> Unit) {
    val text = error ?: message ?: return
    Surface(color = if (error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, contentDescription = AppText.get("Dismiss"))
            }
        }
    }
}

@Composable
private fun StatusText(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AppText.get("Cancel")) } }
    )
}

private fun formatFrequency(value: Float?): String = value?.let { "${(it * 100f).toInt()}%" } ?: "—"

private fun formatDateTime(timestampMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestampMs))

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1_024L) return "$bytes B"
    if (bytes < 1_048_576L) return "%.1f KB".format(bytes / 1_024.0)
    if (bytes < 1_073_741_824L) return "%.1f MB".format(bytes / 1_048_576.0)
    return "%.1f GB".format(bytes / 1_073_741_824.0)
}

internal fun possibleSpeciesItemKey(speciesKey: String): String = "possible:$speciesKey"

@Composable
private fun SpeciesSelectAll(selected: Int, total: Int, onSelect: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = total > 0) { onSelect(selected != total) }, verticalAlignment = Alignment.CenterVertically) {
        TriStateCheckbox(
            state = when { selected == 0 -> ToggleableState.Off; selected == total -> ToggleableState.On; else -> ToggleableState.Indeterminate },
            onClick = { onSelect(selected != total) }, enabled = total > 0
        )
        Text(if (selected == total && total > 0) AppText.get("清空") else AppText.get("全选"), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(AppText.format("已选 {0} / {1}", selected, total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun rareSpeciesItemKey(speciesKey: String): String = "rare:$speciesKey"
