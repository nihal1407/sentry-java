package io.sentry.samples.android.navigation

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import io.sentry.Sentry
import io.sentry.compose.SentryModifier.sentryTag
import io.sentry.compose.SentryTraced
import io.sentry.compose.navigation3.SentryNavEffect
import io.sentry.compose.navigation3.SentryNavOptions
import io.sentry.samples.android.GithubAPI
import io.sentry.samples.android.R
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Sample app Activity for testing Sentry's
 * [Nav3](https://developer.android.com/guide/navigation/navigation-3) integrations.
 *
 * Look at Google's [nav3-recipes](https://github.com/android/nav3-recipes) for helpful patterns to
 * test against. (This Activity doesn't address all of them yet, so update its implementation as
 * needed.)
 */
class Nav3Activity : ComponentActivity() {

  private lateinit var previousConfig: NavigationSampleConfigSnapshot
  private val performanceState = NavigationPerformanceState(measureRenderLatency = true)
  private var performanceRunRequest by mutableStateOf<NavigationPerformanceRunRequest?>(null)
  private var nextPerformanceRunRequestId = 0
  private var isTransactionHistoryActive = false
  private val transactionHistory =
    NavigationTransactionHistory(isActive = { isTransactionHistoryActive })
  private var showActivityUiLoadTransactionDelayMessage = false
  private var routeWorkOptions by
    mutableStateOf(setOf(RouteWorkOption.HTTP_REQUEST, RouteWorkOption.MANUAL_CHILD_SPAN))
  private var showTransactionHistorySheet by mutableStateOf(false)
  private var showCrashConfirmation by mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    previousConfig =
      intent.previousNav3SampleConfigSnapshot(currentNavigationSampleConfigSnapshot())

    val configuration = intent.nav3SampleConfig()
    configuration.applyToCurrentOptions()
    showActivityUiLoadTransactionDelayMessage = configuration.hasOnlyActivityUiLoadTransactions
    transactionHistory.install()

    val initialPerformancePreset =
      intent.getStringExtra(NAV3_PERFORMANCE_PRESET_EXTRA)?.let { presetName ->
        NavigationPerformancePreset.entries.firstOrNull { it.name == presetName }
      }
    val initialPerformanceRun =
      intent.getStringExtra(NAV3_PERFORMANCE_RUN_EXTRA)?.let { runName ->
        NavigationPerformanceRun.entries.firstOrNull { it.name == runName }
      }
    setContent {
      MaterialTheme {
        Nav3SampleApp(
          performanceState = performanceState,
          initialPerformancePreset = initialPerformancePreset,
          initialPerformanceRun = initialPerformanceRun,
          performanceRunRequest = performanceRunRequest,
          configuration = configuration,
          transactions = transactionHistory.transactions,
          showActivityUiLoadTransactionDelayMessage = showActivityUiLoadTransactionDelayMessage,
          routeWorkOptions = routeWorkOptions,
          showTransactionHistorySheet = showTransactionHistorySheet,
          showCrashConfirmation = showCrashConfirmation,
          onShowTransactionHistorySheet = { showTransactionHistorySheet = true },
          onDismissTransactionHistorySheet = { showTransactionHistorySheet = false },
          onShowRouteWorkSettings = { showRouteWorkSettings() },
          onShowCrashConfirmation = { showCrashConfirmation = true },
          onDismissCrashConfirmation = { showCrashConfirmation = false },
          onOpenTransaction = { url -> openTransactionInSentry(url) },
          onDumpTransactionUrl = { url -> dumpTransactionUrl(url) },
          onCopyTransactionUrl = { url -> copyTransactionUrl(url) },
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    val runName = intent.getStringExtra(NAV3_PERFORMANCE_RUN_EXTRA) ?: return
    val run = NavigationPerformanceRun.entries.firstOrNull { it.name == runName } ?: return
    val skipWarmUp = intent.getBooleanExtra(NAV3_PERFORMANCE_SKIP_WARM_UP_EXTRA, false)
    if (!skipWarmUp || !performanceState.benchmarkRunning) {
      performanceState.startBenchmark("Starting")
    }
    performanceRunRequest =
      NavigationPerformanceRunRequest(
        id = ++nextPerformanceRunRequestId,
        run = run,
        warmUpOnly = intent.getBooleanExtra(NAV3_PERFORMANCE_WARM_UP_ONLY_EXTRA, false),
        skipWarmUp = skipWarmUp,
      )
  }

  override fun onStart() {
    super.onStart()
    isTransactionHistoryActive = true
  }

  override fun onStop() {
    isTransactionHistoryActive = false
    performanceState.stopAutomaticWork()
    super.onStop()
  }

  override fun onDestroy() {
    if (isFinishing) {
      previousConfig.applyToCurrentOptions()
    }
    transactionHistory.uninstall()
    super.onDestroy()
  }

  private fun openTransactionInSentry(url: String) {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
  }

  private fun dumpTransactionUrl(url: String) {
    Log.i(NAV3_TAG, "Sentry transaction URL: $url")
    Toast.makeText(this, "Dumped transaction URL to logcat.", Toast.LENGTH_SHORT).show()
  }

  private fun copyTransactionUrl(url: String) {
    val clipboard = getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Sentry transaction URL", url))
    Toast.makeText(this, "Copied transaction URL to clipboard.", Toast.LENGTH_SHORT).show()
  }

  private fun showRouteWorkSettings() {
    showRouteWorkDialog(this, routeWorkOptions) { selectedOptions ->
      routeWorkOptions = selectedOptions
    }
  }
}

@SuppressLint("ContextCastToActivity")
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun Nav3SampleApp(
  performanceState: NavigationPerformanceState,
  initialPerformancePreset: NavigationPerformancePreset? = null,
  initialPerformanceRun: NavigationPerformanceRun? = null,
  performanceRunRequest: NavigationPerformanceRunRequest? = null,
  configuration: NavigationSampleConfig,
  transactions: List<NavigationTransactionTrace>,
  showActivityUiLoadTransactionDelayMessage: Boolean,
  routeWorkOptions: Set<RouteWorkOption>,
  showTransactionHistorySheet: Boolean,
  showCrashConfirmation: Boolean,
  onShowTransactionHistorySheet: () -> Unit,
  onDismissTransactionHistorySheet: () -> Unit,
  onShowRouteWorkSettings: () -> Unit,
  onShowCrashConfirmation: () -> Unit,
  onDismissCrashConfirmation: () -> Unit,
  onOpenTransaction: (String) -> Unit,
  onDumpTransactionUrl: (String) -> Unit,
  onCopyTransactionUrl: (String) -> Unit,
) {
  val activity = LocalContext.current as? ComponentActivity
  val initialScenario =
    if (configuration.enableActivityUiLoadTransaction) {
      Nav3Scenario.SINGLE_STACK
    } else {
      Nav3Scenario.LANDING
    }
  // Covers the saveable-backstack recipe without a separate scenario.
  val backStack = rememberSaveableNav3BackStack(initialScenario.initialRoute)
  val dialogSceneStrategy = remember { Nav3DialogSceneStrategy<Nav3Route>() }
  val bottomSheetSceneStrategy = remember { Nav3BottomSheetSceneStrategy<Nav3Route>() }

  var enableNavigationBreadcrumbs by remember {
    mutableStateOf(configuration.enableNavigationBreadcrumbs)
  }
  var enableNavigationTransactions by remember {
    mutableStateOf(configuration.enableNavigationTransactions)
  }
  var captureBackStack by remember { mutableStateOf(configuration.captureBackStack) }
  var maxCapturedBackStackEntries by remember {
    mutableIntStateOf(configuration.maxCapturedBackStackEntries)
  }
  var selectedScenario by rememberSaveable { mutableStateOf(initialScenario) }
  val performanceScope = rememberCoroutineScope()

  if (selectedScenario == Nav3Scenario.PERFORMANCE) {
    @Suppress("UNUSED_EXPRESSION") performanceState.recompositionTick
  }

  val isPerformanceScenario = selectedScenario == Nav3Scenario.PERFORMANCE
  val sentryBackStack = if (isPerformanceScenario) backStack.toList() else backStack
  val integrationMode = performanceState.integrationMode
  val effectiveCaptureBackStack =
    if (isPerformanceScenario) integrationMode.captureBackStack else captureBackStack
  val sentryNavOptions =
    remember(
      enableNavigationBreadcrumbs,
      enableNavigationTransactions,
      effectiveCaptureBackStack,
      maxCapturedBackStackEntries,
    ) {
      SentryNavOptions(
        enableNavigationBreadcrumbs = enableNavigationBreadcrumbs,
        enableNavigationTransactions = enableNavigationTransactions,
        captureBackStack = effectiveCaptureBackStack,
        maxCapturedBackStackEntries = maxCapturedBackStackEntries,
      )
    }
  val performanceExtractorMode = performanceState.extractorMode
  val nameExtractor =
    remember(isPerformanceScenario, performanceExtractorMode) {
      { route: Nav3Route ->
        if (isPerformanceScenario) {
          performanceState.recordNameExtractor("Nav3Stress.nameExtractor") {
            consumeNavigationPerformanceExtractorWork(
              performanceExtractorMode,
              route.performanceSeed,
            )
            route.routeName
          }
        } else {
          route.routeName
        }
      }
    }
  val performanceArgumentMode = performanceState.argumentMode
  val argumentsExtractor =
    if (isPerformanceScenario && !integrationMode.includeArguments) {
      null
    } else {
      remember(isPerformanceScenario, performanceExtractorMode, performanceArgumentMode) {
        { route: Nav3Route ->
          if (isPerformanceScenario) {
            performanceState.recordArgumentsExtractor("Nav3Stress.argumentsExtractor") {
              consumeNavigationPerformanceExtractorWork(
                performanceExtractorMode,
                route.performanceSeed,
              )
              if (route is Nav3Route.Performance) {
                navigationPerformanceArguments(
                  performanceArgumentMode,
                  route.index,
                  route.generation,
                )
              } else {
                route.arguments
              }
            }
          } else {
            route.arguments
          }
        }
      }
    }

  if (!isPerformanceScenario || integrationMode != NavigationPerformanceIntegrationMode.DISABLED) {
    val nameExtractorCallsBefore = performanceState.nameExtractorCalls
    val argumentsExtractorCallsBefore = performanceState.argumentsExtractorCalls
    val extractorNanosBefore =
      performanceState.nameExtractorNanos + performanceState.argumentsExtractorNanos
    val startedAtNanos = System.nanoTime()
    if (isPerformanceScenario) {
      android.os.Trace.beginSection(performanceState.sentryNavEffectTraceSection())
    }
    SentryNavEffect(
      backStack = sentryBackStack,
      options = sentryNavOptions,
      nameExtractor = nameExtractor,
      argumentsExtractor = argumentsExtractor,
    )
    if (isPerformanceScenario) {
      android.os.Trace.endSection()
      performanceState.recordSentryNavEffect(
        durationNanos = System.nanoTime() - startedAtNanos,
        nameExtractorCallsBefore = nameExtractorCallsBefore,
        argumentsExtractorCallsBefore = argumentsExtractorCallsBefore,
        extractorNanosBefore = extractorNanosBefore,
      )
    }
  }

  SideEffect { performanceState.recordComposition() }

  val applyPerformancePreset: (NavigationPerformancePreset) -> Unit = { preset ->
    if (!performanceState.benchmarkRunning) {
      performanceScope.launch {
        prepareNavigationPerformancePreset(
          preset = preset,
          state = performanceState,
          backStack = backStack,
          onMaxCapturedBackStackEntriesChange = { maxCapturedBackStackEntries = it },
        )
      }
    }
  }
  val runPerformanceBenchmark: (NavigationPerformanceRun) -> Unit = { run ->
    if (!performanceState.benchmarkRunning) {
      performanceScope.launch {
        runNavigationPerformanceBenchmark(
          run = run,
          state = performanceState,
          backStack = backStack,
        )
      }
    }
  }

  LaunchedEffect(initialPerformancePreset, initialPerformanceRun) {
    val preset = initialPerformancePreset ?: return@LaunchedEffect
    selectedScenario = Nav3Scenario.PERFORMANCE
    backStack.openScenario(Nav3Scenario.PERFORMANCE)
    awaitNavigationPerformanceFrames()
    prepareNavigationPerformancePreset(
      preset = preset,
      state = performanceState,
      backStack = backStack,
      onMaxCapturedBackStackEntriesChange = { maxCapturedBackStackEntries = it },
    )
    if (initialPerformanceRun != null) {
      runNavigationPerformanceBenchmark(
        run = initialPerformanceRun,
        state = performanceState,
        backStack = backStack,
      )
    }
  }

  LaunchedEffect(performanceRunRequest) {
    val request = performanceRunRequest ?: return@LaunchedEffect
    runNavigationPerformanceBenchmark(
      run = request.run,
      state = performanceState,
      backStack = backStack,
      warmUpOnly = request.warmUpOnly,
      skipWarmUp = request.skipWarmUp,
    )
  }

  Scaffold(
    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
    topBar = {
      Nav3TopBar(
        backStack = backStack,
        selectedScenario = selectedScenario,
        maxCapturedBackStackEntries = maxCapturedBackStackEntries,
        onTransactionHistoryClick = onShowTransactionHistorySheet,
        onRouteWorkSettingsClick = onShowRouteWorkSettings,
        onScenarioSelected = { scenario ->
          if (scenario != Nav3Scenario.PERFORMANCE && performanceState.benchmarkRunning) {
            performanceState.cancelBenchmark()
          }
          selectedScenario = scenario
          performanceState.stopAutomaticWork()
          backStack.openScenario(scenario)
          if (scenario == Nav3Scenario.PERFORMANCE) {
            performanceState.resetCounters()
            performanceState.markNavigationMutation()
          }
        },
      )
    },
    bottomBar = {
      SentryControls(
        onCaptureException = { captureSampleException("Nav3") },
        onCrashApp = onShowCrashConfirmation,
      )
    },
  ) { innerPadding ->
    Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      Box(modifier = Modifier.weight(1f)) {
        NavDisplay(
          backStack = backStack,
          modifier =
            Modifier.fillMaxSize().drawWithContent {
              drawContent()
              performanceState.recordFirstDraw()
            },
          onBack = {
            if (backStack.size > 1) {
              backStack.removeLastOrNull()
            } else {
              activity?.finish()
            }
          },
          sceneStrategies = listOf(dialogSceneStrategy, bottomSheetSceneStrategy),
          entryProvider =
            entryProvider {
              entry<Nav3Route.SingleStack> { route ->
                TracedNav3Route(route, selectedScenario) {
                  Nav3RouteWorkEffect(route, routeWorkOptions)
                  SingleStackRoute(backStack)
                }
              }
              entry<Nav3Route.Landing> { route ->
                TracedNav3Route(route, selectedScenario) {
                  Nav3RouteWorkEffect(route, routeWorkOptions)
                  LandingRoute()
                }
              }
              entry<Nav3Route.DialogsAndSheets> { route ->
                TracedNav3Route(route, selectedScenario) {
                  Nav3RouteWorkEffect(route, routeWorkOptions)
                  DialogsAndSheetsRoute(backStack)
                }
              }
              entry<Nav3Route.DeepLink> { route ->
                TracedNav3Route(route, selectedScenario) {
                  Nav3RouteWorkEffect(route, routeWorkOptions)
                  DeepLinkRoute(backStack)
                }
              }
              entry<Nav3Route.ProductList> { route ->
                TracedNav3Route(route, selectedScenario) {
                  Nav3RouteWorkEffect(route, routeWorkOptions)
                  ProductListRoute(backStack)
                }
              }
              entry<Nav3Route.ProductDetail> { route ->
                TracedNav3Route(route, selectedScenario) {
                  Nav3RouteWorkEffect(route, routeWorkOptions)
                  ProductDetailRoute(route, backStack)
                }
              }
              entry<Nav3Route.Checkout> { route ->
                TracedNav3Route(route, selectedScenario) {
                  Nav3RouteWorkEffect(route, routeWorkOptions)
                  CheckoutRoute(route, backStack)
                }
              }
              entry<Nav3Route.Confirmation> { route ->
                TracedNav3Route(route, selectedScenario) {
                  Nav3RouteWorkEffect(route, routeWorkOptions)
                  ConfirmationRoute(route, backStack)
                }
              }
              entry<Nav3Route.PromoDialog>(metadata = Nav3DialogSceneStrategy.dialog()) { route ->
                TracedNav3Route(route, selectedScenario) {
                  Nav3RouteWorkEffect(route, routeWorkOptions)
                  PromoDialogRoute(
                    route = route,
                    backStack = backStack,
                    onCaptureException = { captureSampleException("Nav3") },
                    onCrashApp = onShowCrashConfirmation,
                  )
                }
              }
              entry<Nav3Route.ShareSheet>(metadata = Nav3BottomSheetSceneStrategy.bottomSheet()) {
                route ->
                TracedNav3Route(route, selectedScenario) {
                  Nav3RouteWorkEffect(route, routeWorkOptions)
                  ShareSheetRoute(
                    route = route,
                    backStack = backStack,
                    onCaptureException = { captureSampleException("Nav3") },
                    onCrashApp = onShowCrashConfirmation,
                  )
                }
              }
              entry<Nav3Route.Multipane> { route ->
                TracedNav3Route(route, selectedScenario) {
                  Nav3RouteWorkEffect(route, routeWorkOptions)
                  FutureRoute(routeName = "Multipane", scenario = "multipane")
                }
              }
              entry<Nav3Route.Multistack> { route ->
                TracedNav3Route(route, selectedScenario) {
                  Nav3RouteWorkEffect(route, routeWorkOptions)
                  FutureRoute(routeName = "Multiple Stacks", scenario = "multistack")
                }
              }
              entry<Nav3Route.Performance> { route ->
                TracedNav3Route(route, selectedScenario) {
                  Nav3RouteWorkEffect(route, routeWorkOptions)
                  Nav3PerformanceRoute(
                    route = route,
                    backStack = backStack,
                    performanceState = performanceState,
                    maxCapturedBackStackEntries = maxCapturedBackStackEntries,
                    onMaxCapturedBackStackEntriesChange = {
                      maxCapturedBackStackEntries = it
                    },
                    onApplyPreset = applyPerformancePreset,
                    onRunBenchmark = runPerformanceBenchmark,
                  )
                }
              }
            },
        )
      }
    }
  }

  if (showTransactionHistorySheet) {
    NavigationTransactionHistorySheet(
      sampleName = "Nav3",
      transactions = transactions,
      showActivityUiLoadTransactionDelayMessage = showActivityUiLoadTransactionDelayMessage,
      onDismissRequest = onDismissTransactionHistorySheet,
      onOpenTransaction = onOpenTransaction,
      onDumpTransactionUrl = onDumpTransactionUrl,
      onCopyTransactionUrl = onCopyTransactionUrl,
    )
  }

  if (showCrashConfirmation) {
    AlertDialog(
      onDismissRequest = onDismissCrashConfirmation,
      title = { Text("Crash app?") },
      text = { Text("This will throw an uncaught exception and close the sample app.") },
      dismissButton = {
        TextButton(onClick = onDismissCrashConfirmation) { Text("Cancel") }
      },
      confirmButton = {
        TextButton(
          onClick = {
            onDismissCrashConfirmation()
            crashSampleApp("Nav3")
          }
        ) {
          Text("Crash")
        }
      },
    )
  }
}

@ExperimentalComposeUiApi
@Composable
private fun TracedNav3Route(
  route: Nav3Route,
  scenario: Nav3Scenario,
  content: @Composable BoxScope.() -> Unit,
) {
  tagCurrentNav3Scenario(scenario)
  SentryTraced(
    tag = "Nav3 /${route.routeName}",
    enableUserInteractionTracing = false,
    content = content,
  )
}

private fun tagCurrentNav3Scenario(scenario: Nav3Scenario) {
  Sentry.getSpan()?.setTag("sample_nav3_scenario", scenario.label)
  tagCurrentNavigationSampleScenario(scenario.label)
}

@Composable
private fun Nav3TopBar(
  backStack: List<Nav3Route>,
  selectedScenario: Nav3Scenario,
  maxCapturedBackStackEntries: Int,
  onTransactionHistoryClick: () -> Unit,
  onRouteWorkSettingsClick: () -> Unit,
  onScenarioSelected: (Nav3Scenario) -> Unit,
) {
  val currentRoute = backStack.lastOrNull() ?: Nav3Route.SingleStack
  val currentRouteText = currentRoute.displayRoute()
  val capturedBackStackEntries =
    backStack.takeLast(maxCapturedBackStackEntries).map { route -> "/${route.previewName}" }
  val capturedBackStack =
    capturedBackStackEntries
      .mapIndexed { index, route ->
        if (index == 0 && backStack.size > maxCapturedBackStackEntries) {
          "... $route"
        } else {
          route
        }
      }
      .joinToString(" -> ")

  Surface(shadowElevation = 4.dp) {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 18.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          "Navigation 3",
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.weight(1f),
        )
        IconButton(
          onClick = onTransactionHistoryClick,
          modifier = Modifier.sentryTag(nav3InteractionTag("Recent Transactions")),
        ) {
          Icon(
            imageVector = Icons.Filled.AccountTree,
            contentDescription = "Recent transactions",
          )
        }
        IconButton(
          onClick = onRouteWorkSettingsClick,
          modifier = Modifier.sentryTag(nav3InteractionTag("Route Work Settings")),
        ) {
          Icon(imageVector = Icons.Filled.Settings, contentDescription = "Route work settings")
        }
      }
      Column(
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = "Current route: $currentRouteText",
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.horizontalScroll(rememberScrollState()),
          maxLines = 1,
        )
        Text(
          text = "Nav3 back stack: $capturedBackStack",
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.horizontalScroll(rememberScrollState()),
          maxLines = 1,
        )
      }
      ScenarioBar(selectedScenario = selectedScenario, onScenarioSelected = onScenarioSelected)
    }
  }
}

private fun Nav3Route.displayRoute(): String = routeSpec().displayRoute(arguments)

private fun Nav3Route.routeSpec(): Nav2RouteSpec =
  when (this) {
    Nav3Route.Landing -> Nav2RouteSpecs.landing
    Nav3Route.SingleStack -> Nav2RouteSpecs.home
    Nav3Route.DeepLink ->
      Nav2RouteSpec(
        routeName = Nav3Route.DeepLink.routeName,
        title = "Deep Link",
        description =
          "Simulates opening a deep link that builds a synthetic backstack before landing on a " +
            "detail destination.",
      )
    Nav3Route.ProductList -> Nav2RouteSpecs.productList
    is Nav3Route.ProductDetail -> Nav2RouteSpecs.productDetail
    is Nav3Route.Checkout -> Nav2RouteSpecs.checkout
    is Nav3Route.Confirmation -> Nav2RouteSpecs.confirmation
    is Nav3Route.PromoDialog -> Nav2RouteSpecs.promoDialog
    is Nav3Route.ShareSheet -> Nav2RouteSpecs.shareSheet
    Nav3Route.DialogsAndSheets ->
      Nav2RouteSpec(
        routeName = Nav3Route.DialogsAndSheets.routeName,
        title = "Dialogs & Sheets",
        description =
          "These destinations use Nav3 scene metadata and overlay scene strategies while the Sentry " +
            "controls remain visible in the Activity bottom bar.",
      )
    Nav3Route.Multipane,
    Nav3Route.Multistack,
    is Nav3Route.Performance -> Nav2RouteSpec(routeName = routeName, title = routeName)
  }

@Composable
private fun ScenarioBar(
  selectedScenario: Nav3Scenario,
  onScenarioSelected: (Nav3Scenario) -> Unit,
) {
  val scenarios = Nav3Scenario.entries.filter { scenario -> scenario.showTab }

  Row(
    modifier =
      Modifier.fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(start = 24.dp, end = 24.dp)
  ) {
    scenarios.forEach { scenario ->
      val selected = selectedScenario == scenario
      Column(
        modifier =
          Modifier.defaultMinSize(minWidth = 120.dp).clickable { onScenarioSelected(scenario) },
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          text = scenario.label,
          modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
          color =
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onBackground,
          fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .height(3.dp)
              .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
              )
        )
      }
    }
  }
}

private fun SnapshotStateList<Nav3Route>.openScenario(scenario: Nav3Scenario) {
  when (scenario) {
    Nav3Scenario.LANDING -> resetTo(Nav3Route.Landing)
    Nav3Scenario.SINGLE_STACK -> resetTo(Nav3Route.SingleStack)
    Nav3Scenario.DIALOGS_SHEETS -> resetTo(Nav3Route.DialogsAndSheets)
    Nav3Scenario.DEEP_LINK -> resetTo(Nav3Route.DeepLink)
    Nav3Scenario.MULTIPANE -> resetTo(Nav3Route.Multipane)
    Nav3Scenario.MULTIPLE_STACKS -> resetTo(Nav3Route.Multistack)
    Nav3Scenario.PERFORMANCE -> resetTo(Nav3Route.Performance(index = 0, generation = 0))
  }
}

@Composable
private fun SentryControls(
  onCaptureException: () -> Unit,
  onCrashApp: () -> Unit,
) {
  Surface(shadowElevation = 8.dp) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.Bottom,
    ) {
      Row(
        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
      ) {
        Nav3SentryButton(
          label = "Capture Exception",
          onClick = onCaptureException,
          modifier = Modifier.weight(1f),
        )
        Nav3SentryButton(
          label = "Crash App",
          onClick = onCrashApp,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
private fun Nav3SentryButton(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  interactionLabel: String = label,
) {
  Button(
    onClick = onClick,
    modifier = modifier.sentryTag(nav3InteractionTag(interactionLabel)),
    colors =
      ButtonDefaults.buttonColors(
        containerColor = colorResource(R.color.colorAccentSoft),
        contentColor = Color.White,
      ),
  ) {
    Text(label)
  }
}

@Composable
private fun Nav3RouteWorkEffect(
  route: Nav3Route,
  routeWorkOptions: Set<RouteWorkOption>,
) {
  val currentOptions = rememberUpdatedState(routeWorkOptions)

  if (RouteWorkOption.MANUAL_CHILD_SPAN in currentOptions.value) {
    // Keep this synchronous to verify that Nav3 route transactions are bound before destination
    // composition runs, not merely before destination effects are launched.
    runManualNav3RouteActivationSpan(route)
  }

  LaunchedEffect(route) {
    runNav3RouteWork(
      route = route,
      options = currentOptions.value,
    )
  }
}

private suspend fun runNav3RouteWork(
  route: Nav3Route,
  options: Set<RouteWorkOption>,
) {
  RouteWorkOption.entries.forEach { option ->
    if (option !in options || option == RouteWorkOption.MANUAL_CHILD_SPAN) {
      return@forEach
    }

    tagNav3SampleAction(option.tagName, route)
    when (option) {
      RouteWorkOption.HTTP_REQUEST -> {
        try {
          GithubAPI.service.listReposAsync("getsentry", 5)
        } catch (e: IOException) {
          Sentry.captureException(e)
        } catch (e: HttpException) {
          Sentry.captureException(e)
        } finally {
          withContext(Dispatchers.IO) { Sentry.flush(SENTRY_FLUSH_TIMEOUT_MILLIS) }
        }
      }
      RouteWorkOption.MANUAL_CHILD_SPAN -> Unit
    }
  }
}

private fun captureSampleException(navName: String) {
  Sentry.captureException(RuntimeException("$navName sample exception button"))
  Thread { Sentry.flush(SENTRY_FLUSH_TIMEOUT_MILLIS) }.start()
}

private fun crashSampleApp(navName: String): Nothing {
  throw RuntimeException("Fatal $navName sample crash button")
}

private fun runManualNav3RouteActivationSpan(route: Nav3Route) {
  val span =
    Sentry.getSpan()
      ?.startChild(
        "test.navigation.route_activation",
        "Nav3 /${route.routeName} route activation",
      )
  span?.setData("sample.route_activation", true)
  span?.finish()
}

@Composable
private fun SingleStackRoute(backStack: SnapshotStateList<Nav3Route>) {
  RouteScaffold(routeSpec = Nav2RouteSpecs.home) {
    RouteButton("Browse Products") { backStack.add(Nav3Route.ProductList) }
  }
}

@Composable
private fun LandingRoute() {
  LaunchedEffect(Unit) { cancelCurrentActivityUiLoadTransaction() }
  RouteScaffold(routeSpec = Nav2RouteSpecs.landing)
}

@Composable
private fun DeepLinkRoute(backStack: SnapshotStateList<Nav3Route>) {
  RouteScaffold(routeSpec = Nav3Route.DeepLink.routeSpec()) {
    RouteButton("Go to deep link destination") { backStack.openSyntheticProductDeepLink() }
  }
}

@Composable
private fun DialogsAndSheetsRoute(backStack: SnapshotStateList<Nav3Route>) {
  RouteScaffold(routeSpec = Nav3Route.DialogsAndSheets.routeSpec()) {
    RouteButton("Show Dialog Destination") {
      backStack.add(Nav3Route.PromoDialog(promoId = "summer-sale"))
    }
    RouteButton("Show Bottom Sheet Destination") {
      backStack.add(Nav3Route.ShareSheet(productId = "home"))
    }
  }
}

@Composable
private fun ProductListRoute(backStack: SnapshotStateList<Nav3Route>) {
  var showProductList by rememberSaveable { mutableStateOf(true) }

  RouteScaffold(routeSpec = Nav2RouteSpecs.productList) {
    RouteButton(if (showProductList) "Hide Product List" else "Add Product List") {
      showProductList = !showProductList
    }
    if (showProductList) {
      RouteButton("Open Product 42") {
        backStack.add(
          Nav3Route.ProductDetail(
            productId = "42",
            source = "product-list",
            campaign = "summer-sale",
          )
        )
      }
      RouteButton("Open Product 7") {
        backStack.add(Nav3Route.ProductDetail(productId = "7", source = "product-list"))
      }
    }
  }
}

@Composable
private fun ProductDetailRoute(
  route: Nav3Route.ProductDetail,
  backStack: SnapshotStateList<Nav3Route>,
) {
  LaunchedEffect(route.productId, route.source, route.campaign) {
    recordSimulatedBackgroundSpan(Nav2RouteNames.PRODUCT_DETAIL, "Nav3")
  }

  RouteScaffold(routeSpec = Nav2RouteSpecs.productDetail) {
    Nav2RouteSpecs.productDetail.displayArguments(route.arguments).forEach { (label, value) ->
      RouteInfo(label, value)
    }
    RouteButton("Show Promo Dialog") {
      backStack.add(Nav3Route.PromoDialog("detail-${route.productId}"))
    }
    RouteButton("Open Share Sheet") {
      backStack.add(Nav3Route.ShareSheet(route.productId))
    }
    RouteButton("Go to Checkout") { backStack.add(Nav3Route.Checkout(route.productId)) }
  }
}

@Composable
private fun CheckoutRoute(route: Nav3Route.Checkout, backStack: SnapshotStateList<Nav3Route>) {
  RouteScaffold(routeSpec = Nav2RouteSpecs.checkout) {
    Nav2RouteSpecs.checkout.displayArguments(route.arguments).forEach { (label, value) ->
      RouteInfo(label, value)
    }
    RouteButton("Complete Order") {
      backStack.add(Nav3Route.Confirmation(orderId = "order-${route.productId}"))
    }
  }
}

@Composable
private fun ConfirmationRoute(
  route: Nav3Route.Confirmation,
  backStack: SnapshotStateList<Nav3Route>,
) {
  RouteScaffold(routeSpec = Nav2RouteSpecs.confirmation) {
    Nav2RouteSpecs.confirmation.displayArguments(route.arguments).forEach { (label, value) ->
      RouteInfo(label, value)
    }
    RouteButton("Reset Backstack") { backStack.resetTo(Nav3Route.SingleStack) }
  }
}

@Composable
private fun PromoDialogRoute(
  route: Nav3Route.PromoDialog,
  backStack: SnapshotStateList<Nav3Route>,
  onCaptureException: () -> Unit,
  onCrashApp: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
  ) {
    Column(
      modifier = Modifier.padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      val routeSpec = Nav2RouteSpecs.promoDialog
      Text(routeSpec.title, style = MaterialTheme.typography.headlineSmall)
      routeSpec.description?.let { Text(it) }
      routeSpec.displayArguments(route.arguments).forEach { (label, value) ->
        Text("$label=$value")
      }
      Nav3SentryButton(
        label = "Capture Exception",
        onClick = onCaptureException,
        modifier = Modifier.fillMaxWidth(),
        interactionLabel = "Promo Dialog Exception",
      )
      Nav3SentryButton(
        label = "Crash App",
        onClick = onCrashApp,
        modifier = Modifier.fillMaxWidth(),
        interactionLabel = "Promo Dialog Crash App",
      )
      Button(
        onClick = { backStack.removeLastOrNull() },
        modifier = Modifier.fillMaxWidth().sentryTag(nav3InteractionTag("Promo Dialog Dismiss")),
      ) {
        Text("Dismiss")
      }
    }
  }
}

@Composable
private fun ShareSheetRoute(
  route: Nav3Route.ShareSheet,
  backStack: SnapshotStateList<Nav3Route>,
  onCaptureException: () -> Unit,
  onCrashApp: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    val routeSpec = Nav2RouteSpecs.shareSheet
    Text(routeSpec.title, style = MaterialTheme.typography.headlineSmall)
    routeSpec.description?.let { Text(it) }
    routeSpec.displayArguments(route.arguments).forEach { (label, value) -> Text("$label=$value") }
    Nav3SentryButton(
      label = "Capture Exception",
      onClick = onCaptureException,
      modifier = Modifier.fillMaxWidth(),
      interactionLabel = "Share Sheet Exception",
    )
    Nav3SentryButton(
      label = "Crash App",
      onClick = onCrashApp,
      modifier = Modifier.fillMaxWidth(),
      interactionLabel = "Share Sheet Crash App",
    )
    Button(
      onClick = { backStack.removeLastOrNull() },
      modifier = Modifier.fillMaxWidth().sentryTag(nav3InteractionTag("Share Sheet Done")),
    ) {
      Text("Done")
    }
    Spacer(Modifier.height(12.dp))
  }
}

private fun nav3InteractionTag(label: String): String = "Nav3 $label"

@Composable
private fun rememberSaveableNav3BackStack(initialRoute: Nav3Route): SnapshotStateList<Nav3Route> {
  return rememberSaveable(saver = nav3BackStackSaver()) {
    mutableStateListOf(initialRoute)
  }
}

private fun nav3BackStackSaver() =
  listSaver<SnapshotStateList<Nav3Route>, Bundle>(
    save = { stack -> stack.map { route -> route.toSavedState() } },
    restore = { savedRoutes ->
      mutableStateListOf<Nav3Route>().apply {
        addAll(savedRoutes.map { savedRoute -> savedRoute.toNav3Route() })
        if (isEmpty()) {
          add(Nav3Route.SingleStack)
        }
      }
    },
  )

private fun Nav3Route.toSavedState(): Bundle =
  Bundle().apply {
    when (this@toSavedState) {
      Nav3Route.Landing -> putString("type", "landing")
      Nav3Route.SingleStack -> putString("type", "single_stack")
      Nav3Route.DialogsAndSheets -> putString("type", "dialogs_and_sheets")
      Nav3Route.DeepLink -> putString("type", "deep_link")
      Nav3Route.ProductList -> putString("type", "product_list")
      is Nav3Route.ProductDetail -> {
        putString("type", "product_detail")
        putString("product_id", productId)
        putString("source", source)
        putString("campaign", campaign)
      }
      is Nav3Route.Checkout -> {
        putString("type", "checkout")
        putString("product_id", productId)
      }
      is Nav3Route.Confirmation -> {
        putString("type", "confirmation")
        putString("order_id", orderId)
      }
      is Nav3Route.PromoDialog -> {
        putString("type", "promo_dialog")
        putString("promo_id", promoId)
      }
      is Nav3Route.ShareSheet -> {
        putString("type", "share_sheet")
        putString("product_id", productId)
      }
      Nav3Route.Multipane -> putString("type", "multipane")
      Nav3Route.Multistack -> putString("type", "multistack")
      is Nav3Route.Performance -> {
        putString("type", "performance")
        putInt("index", index)
        putInt("generation", generation)
      }
    }
  }

private fun Bundle.toNav3Route(): Nav3Route {
  return when (getString("type")) {
    "landing" -> Nav3Route.Landing
    "single_stack" -> Nav3Route.SingleStack
    "dialogs_and_sheets" -> Nav3Route.DialogsAndSheets
    "deep_link" -> Nav3Route.DeepLink
    "product_list" -> Nav3Route.ProductList
    "product_detail" ->
      Nav3Route.ProductDetail(
        productId = requireNotNull(getString("product_id")),
        source = requireNotNull(getString("source")),
        campaign = getString("campaign"),
      )
    "checkout" -> Nav3Route.Checkout(productId = requireNotNull(getString("product_id")))
    "confirmation" -> Nav3Route.Confirmation(orderId = requireNotNull(getString("order_id")))
    "promo_dialog" -> Nav3Route.PromoDialog(promoId = requireNotNull(getString("promo_id")))
    "share_sheet" -> Nav3Route.ShareSheet(productId = requireNotNull(getString("product_id")))
    "multipane" -> Nav3Route.Multipane
    "multistack" -> Nav3Route.Multistack
    "performance" ->
      Nav3Route.Performance(
        index = getInt("index"),
        generation = getInt("generation"),
      )
    else -> Nav3Route.SingleStack
  }
}

@Composable
private fun FutureRoute(routeName: String, scenario: String) {
  RouteScaffold(
    routeSpec =
      Nav2RouteSpec(
        routeName = routeName,
        title = "$routeName: WIP",
        description =
          "Reserved for a future milestone when SentryNavEffect supports $scenario navigation " +
            "state.",
      )
  )
}

@Composable
private fun Nav3PerformanceRoute(
  route: Nav3Route.Performance,
  backStack: SnapshotStateList<Nav3Route>,
  performanceState: NavigationPerformanceState,
  maxCapturedBackStackEntries: Int,
  onMaxCapturedBackStackEntriesChange: (Int) -> Unit,
  onApplyPreset: (NavigationPerformancePreset) -> Unit,
  onRunBenchmark: (NavigationPerformanceRun) -> Unit,
) {
  val benchmarkRunning = performanceState.benchmarkRunning
  if (benchmarkRunning) {
    Column(
      modifier = Modifier.fillMaxSize().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Performance benchmark", style = MaterialTheme.typography.headlineMedium)
      Text(performanceState.benchmarkStatus, style = MaterialTheme.typography.bodyLarge)
      Text("Diagnostics will be published when the run completes.")
    }
    return
  }

  LaunchedEffect(route) { performanceState.markDestinationChange() }

  NavigationPerformancePanel(
    title = "Performance",
    description =
      "Stress SentryNavEffect with deep stacks, unrelated recompositions, route extraction, and " +
        "argument sanitization. Use Perfetto sections prefixed with Nav3Stress to inspect hot paths.",
    currentRoute = "/${route.previewName}",
    backStack =
      navigationPerformanceBackStackPreview(backStack.map { entry -> "/${entry.previewName}" }),
    state = performanceState,
    showExtractorControls = true,
    onBuildStack = {
      traceNavigationPerformanceSection("Nav3Stress.buildStack") {
        performanceState.beginNavigationOperation()
        backStack.openPerformanceStack(
          depth = performanceState.stackDepth,
          generation = performanceState.nextGeneration(),
        )
        performanceState.markNavigationMutation()
      }
    },
    onMutateLowerEntry = {
      traceNavigationPerformanceSection("Nav3Stress.mutateLowerEntry") {
        performanceState.beginNavigationOperation()
        backStack.mutatePerformanceLowerEntry(performanceState.nextGeneration())
        performanceState.markNavigationMutation()
      }
    },
    onReplaceTop = {
      traceNavigationPerformanceSection("Nav3Stress.replaceTop") {
        performanceState.beginNavigationOperation()
        backStack.replacePerformanceTop(performanceState.nextGeneration())
        performanceState.markNavigationMutation()
      }
    },
    nav3Controls =
      Nav3PerformanceControls(
        actualStackEntries = backStack.size,
        maxCapturedBackStackEntries = maxCapturedBackStackEntries,
        onMaxCapturedBackStackEntriesChange = onMaxCapturedBackStackEntriesChange,
        onApplyPreset = onApplyPreset,
        onRunBenchmark = onRunBenchmark,
      ),
  )
}

private suspend fun prepareNavigationPerformancePreset(
  preset: NavigationPerformancePreset,
  state: NavigationPerformanceState,
  backStack: SnapshotStateList<Nav3Route>,
  onMaxCapturedBackStackEntriesChange: (Int) -> Unit,
) {
  state.startBenchmark("Preparing ${preset.label}")
  try {
    state.stackDepth = preset.stackDepth
    state.extractorMode = preset.extractorMode
    state.argumentMode = preset.argumentMode
    state.updateIntegrationMode(preset.integrationMode)
    onMaxCapturedBackStackEntriesChange(preset.maxCapturedBackStackEntries)
    backStack.openPerformanceStack(preset.stackDepth, state.nextGeneration())
    awaitNavigationPerformanceFrames()
    if (
      !runNavigationPerformanceIterations(
        iterationCount = PERFORMANCE_WARM_UP_ITERATIONS,
        run = NavigationPerformanceRun.TOP_REPLACEMENTS,
        state = state,
        backStack = backStack,
      )
    ) {
      return
    }
    state.resetCounters()
    state.suppressNextDestinationChange()
    state.cancelBenchmark(status = "Ready: ${preset.label}")
  } catch (e: CancellationException) {
    state.cancelBenchmark()
    throw e
  }
}

private suspend fun runNavigationPerformanceBenchmark(
  run: NavigationPerformanceRun,
  state: NavigationPerformanceState,
  backStack: SnapshotStateList<Nav3Route>,
  warmUpOnly: Boolean = false,
  skipWarmUp: Boolean = false,
) {
  if (!skipWarmUp) {
    state.startBenchmark("Warming up")
  }
  try {
    if (run == NavigationPerformanceRun.AB_COMPARISON) {
      runNavigationPerformanceAbComparison(state, backStack)
      return
    }

    if (
      !skipWarmUp &&
        !runNavigationPerformanceIterations(
          iterationCount = PERFORMANCE_WARM_UP_ITERATIONS,
          run = run,
          state = state,
          backStack = backStack,
        )
    ) {
      return
    }
    if (warmUpOnly) {
      state.finishWarmUp()
      return
    }
    state.startMeasuredIterations()
    if (!skipWarmUp) {
      state.updateBenchmarkStatus("Measuring $PERFORMANCE_MEASURED_ITERATIONS iterations")
    }
    if (
      !runNavigationPerformanceIterations(
        iterationCount = PERFORMANCE_MEASURED_ITERATIONS,
        run = run,
        state = state,
        backStack = backStack,
      )
    ) {
      return
    }
    state.finishBenchmark()
  } catch (e: CancellationException) {
    state.cancelBenchmark()
    throw e
  }
}

private suspend fun runNavigationPerformanceAbComparison(
  state: NavigationPerformanceState,
  backStack: SnapshotStateList<Nav3Route>,
) {
  val originalMode = state.integrationMode
  val modes =
    if (state.nextAbComparisonRunsDisabledFirst()) {
      listOf(
        NavigationPerformanceIntegrationMode.DISABLED,
        NavigationPerformanceIntegrationMode.FULL_STACK,
      )
    } else {
      listOf(
        NavigationPerformanceIntegrationMode.FULL_STACK,
        NavigationPerformanceIntegrationMode.DISABLED,
      )
    }
  val results = mutableListOf<String>()

  try {
    modes.forEach { mode ->
      state.updateBenchmarkStatus("Warming up ${mode.label}")
      state.updateIntegrationMode(mode)
      awaitNavigationPerformanceFrames()
      if (
        !runNavigationPerformanceIterations(
          iterationCount = PERFORMANCE_WARM_UP_ITERATIONS,
          run = NavigationPerformanceRun.TOP_REPLACEMENTS,
          state = state,
          backStack = backStack,
        )
      ) {
        return
      }
      state.startMeasuredIterations()
      state.updateBenchmarkStatus("Measuring ${mode.label}")
      state.beginAbPhase(mode)
      val completed =
        try {
          runNavigationPerformanceIterations(
            iterationCount = PERFORMANCE_MEASURED_ITERATIONS,
            run = NavigationPerformanceRun.TOP_REPLACEMENTS,
            state = state,
            backStack = backStack,
          )
        } finally {
          state.finishAbPhase()
        }
      if (!completed) {
        return
      }
      state.stopCollectingMeasurements()
      results += state.benchmarkSummary(mode.label)
    }
  } finally {
    state.updateIntegrationMode(originalMode)
    awaitNavigationPerformanceFrames()
  }

  state.finishBenchmark(results.joinToString(" | "))
}

private suspend fun runNavigationPerformanceIterations(
  iterationCount: Int,
  run: NavigationPerformanceRun,
  state: NavigationPerformanceState,
  backStack: SnapshotStateList<Nav3Route>,
): Boolean {
  repeat(iterationCount) {
    if (!state.benchmarkRunning) {
      return false
    }
    performNavigationPerformanceIteration(run, state, backStack)
    awaitNavigationPerformanceFrames()
  }
  return true
}

private fun performNavigationPerformanceIteration(
  run: NavigationPerformanceRun,
  state: NavigationPerformanceState,
  backStack: SnapshotStateList<Nav3Route>,
) {
  when (run) {
    NavigationPerformanceRun.UNRELATED_RECOMPOSITIONS -> state.markRecompositionRequest()
    NavigationPerformanceRun.TOP_REPLACEMENTS -> {
      state.beginNavigationOperation()
      backStack.replacePerformanceTop(state.nextGeneration())
      state.markNavigationMutation()
      state.markBenchmarkDestinationChange()
    }
    NavigationPerformanceRun.LOWER_ENTRY_MUTATIONS -> {
      state.beginNavigationOperation()
      backStack.mutatePerformanceLowerEntry(state.nextGeneration())
      state.markNavigationMutation()
    }
    NavigationPerformanceRun.AB_COMPARISON -> error("A/B comparison runs its own iterations")
  }
}

private suspend fun awaitNavigationPerformanceFrames() {
  withFrameNanos {}
  withFrameNanos {}
}

private const val PERFORMANCE_WARM_UP_ITERATIONS = 5
private const val PERFORMANCE_MEASURED_ITERATIONS = 20
private const val NAV3_TAG = "Nav3Activity"
internal const val NAV3_PERFORMANCE_PRESET_EXTRA = "nav3_performance_preset"
internal const val NAV3_PERFORMANCE_RUN_EXTRA = "nav3_performance_run"
internal const val NAV3_PERFORMANCE_WARM_UP_ONLY_EXTRA = "nav3_performance_warm_up_only"
internal const val NAV3_PERFORMANCE_SKIP_WARM_UP_EXTRA = "nav3_performance_skip_warm_up"

private data class NavigationPerformanceRunRequest(
  val id: Int,
  val run: NavigationPerformanceRun,
  val warmUpOnly: Boolean,
  val skipWarmUp: Boolean,
)

@Composable
private fun RouteScaffold(
  routeSpec: Nav2RouteSpec,
  content: (@Composable ColumnScope.() -> Unit)? = null,
) {
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      routeSpec.title,
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
    )
    routeSpec.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    if (content != null) {
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          content()
        }
      }
    }
  }
}

@Composable
private fun RouteButton(label: String, onClick: () -> Unit) {
  Button(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().sentryTag(nav3InteractionTag(label)),
  ) {
    Text(label)
  }
}

@Composable
private fun RouteInfo(label: String, value: String) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
        .padding(12.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(label, fontWeight = FontWeight.Bold)
    Spacer(Modifier.size(12.dp))
    Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

private fun SnapshotStateList<Nav3Route>.resetTo(route: Nav3Route) {
  clear()
  add(route)
}

private fun SnapshotStateList<Nav3Route>.openSyntheticProductDeepLink() {
  clear()
  add(Nav3Route.SingleStack)
  add(Nav3Route.ProductList)
  add(Nav3Route.ProductDetail(productId = "42", source = "deep-link", campaign = "email"))
}

private fun SnapshotStateList<Nav3Route>.openPerformanceStack(depth: Int, generation: Int) {
  clear()
  repeat(depth.coerceAtLeast(1)) { index ->
    add(Nav3Route.Performance(index = index, generation = generation))
  }
}

private fun SnapshotStateList<Nav3Route>.mutatePerformanceLowerEntry(generation: Int) {
  if (isEmpty()) {
    add(Nav3Route.Performance(index = 0, generation = generation))
    return
  }

  val index = if (size > 1) 0 else lastIndex
  set(index, Nav3Route.Performance(index = index, generation = generation))
}

private fun SnapshotStateList<Nav3Route>.replacePerformanceTop(generation: Int) {
  if (isEmpty()) {
    add(Nav3Route.Performance(index = 0, generation = generation))
    return
  }

  set(lastIndex, Nav3Route.Performance(index = lastIndex, generation = generation))
}

private fun tagNav3SampleAction(action: String, route: Nav3Route) {
  Sentry.setTag("sample_action", "nav3_$action")
  Sentry.setTag("sample_nav3_route", route.routeName)
}

private sealed interface Nav3Route {
  val routeName: String
  val arguments: Map<String, Any?>
    get() = emptyMap()

  val previewName: String
    get() = routeName

  val performanceSeed: Int
    get() = hashCode()

  data object Landing : Nav3Route {
    override val routeName: String = Nav2RouteNames.LANDING
  }

  data object SingleStack : Nav3Route {
    override val routeName: String = Nav2RouteNames.HOME
  }

  data object DialogsAndSheets : Nav3Route {
    override val routeName: String = "DialogsAndSheets"
  }

  data object DeepLink : Nav3Route {
    override val routeName: String = Nav2RouteNames.DEEP_LINK
  }

  data object ProductList : Nav3Route {
    override val routeName: String = Nav2RouteNames.PRODUCT_LIST
  }

  data class ProductDetail(
    val productId: String,
    val source: String,
    val campaign: String? = null,
  ) : Nav3Route {
    override val routeName: String = Nav2RouteNames.PRODUCT_DETAIL
    override val arguments: Map<String, Any?> =
      mapOf(
          Nav2Args.PRODUCT_ID to productId,
          Nav2Args.SOURCE to source,
          Nav2Args.CAMPAIGN to campaign,
        )
        .filterValues { it != null }
    override val previewName: String = "ProductDetail($productId)"
  }

  data class Checkout(val productId: String) : Nav3Route {
    override val routeName: String = Nav2RouteNames.CHECKOUT
    override val arguments: Map<String, Any?> = mapOf(Nav2Args.PRODUCT_ID to productId)
    override val previewName: String = "Checkout($productId)"
  }

  data class Confirmation(val orderId: String) : Nav3Route {
    override val routeName: String = Nav2RouteNames.CONFIRMATION
    override val arguments: Map<String, Any?> = mapOf(Nav2Args.ORDER_ID to orderId)
    override val previewName: String = "Confirmation($orderId)"
  }

  data class PromoDialog(val promoId: String) : Nav3Route {
    override val routeName: String = Nav2RouteNames.PROMO_DIALOG
    override val arguments: Map<String, Any?> = mapOf(Nav2Args.PROMO_ID to promoId)
    override val previewName: String = "PromoDialog($promoId)"
  }

  data class ShareSheet(val productId: String) : Nav3Route {
    override val routeName: String = Nav2RouteNames.SHARE_SHEET
    override val arguments: Map<String, Any?> = mapOf(Nav2Args.PRODUCT_ID to productId)
    override val previewName: String = "ShareSheet($productId)"
  }

  data object Multipane : Nav3Route {
    override val routeName: String = "Multipane"
    override val arguments: Map<String, Any?> = mapOf("scenario" to "multipane")
  }

  data object Multistack : Nav3Route {
    override val routeName: String = "Multistack"
    override val arguments: Map<String, Any?> = mapOf("scenario" to "multistack")
  }

  data class Performance(val index: Int, val generation: Int) : Nav3Route {
    override val routeName: String = "Performance"
    override val arguments: Map<String, Any?> = mapOf("index" to index, "generation" to generation)
    override val previewName: String = "Performance($index:$generation)"
    override val performanceSeed: Int = 31 * index + generation
  }
}

private enum class Nav3Scenario(val label: String, val showTab: Boolean = true) {
  LANDING(Nav2RouteNames.LANDING, showTab = false),
  SINGLE_STACK("Single Stack"),
  DIALOGS_SHEETS("Dialogs & Sheets", showTab = false),
  DEEP_LINK("Deep Link"),
  MULTIPANE("Multipane"),
  MULTIPLE_STACKS("Multistack"),
  PERFORMANCE("Performance"),
}

private val Nav3Scenario.initialRoute: Nav3Route
  get() =
    when (this) {
      Nav3Scenario.LANDING -> Nav3Route.Landing
      Nav3Scenario.SINGLE_STACK -> Nav3Route.SingleStack
      Nav3Scenario.DIALOGS_SHEETS -> Nav3Route.DialogsAndSheets
      Nav3Scenario.DEEP_LINK -> Nav3Route.DeepLink
      Nav3Scenario.MULTIPANE -> Nav3Route.Multipane
      Nav3Scenario.MULTIPLE_STACKS -> Nav3Route.Multistack
      Nav3Scenario.PERFORMANCE -> Nav3Route.Performance(index = 0, generation = 0)
    }
