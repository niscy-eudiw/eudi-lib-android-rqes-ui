/*
 * Copyright (c) 2023 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

package eu.europa.ec.rqesui.presentation.ui.select_qtsp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import eu.europa.ec.rqesui.R
import eu.europa.ec.rqesui.infrastructure.theme.values.devider
import eu.europa.ec.rqesui.presentation.entities.SelectionItemUi
import eu.europa.ec.rqesui.presentation.extension.finish
import eu.europa.ec.rqesui.presentation.ui.component.SelectionItem
import eu.europa.ec.rqesui.presentation.ui.component.content.ContentScreen
import eu.europa.ec.rqesui.presentation.ui.component.content.ContentTitleWithSubtitle
import eu.europa.ec.rqesui.presentation.ui.component.content.ScreenNavigateAction
import eu.europa.ec.rqesui.presentation.ui.component.preview.PreviewTheme
import eu.europa.ec.rqesui.presentation.ui.component.preview.ThemeModePreviews
import eu.europa.ec.rqesui.presentation.ui.component.utils.SPACING_LARGE
import eu.europa.ec.rqesui.presentation.ui.component.utils.VSpacer
import eu.europa.ec.rqesui.presentation.ui.component.wrap.BottomSheetWithOptionsList
import eu.europa.ec.rqesui.presentation.ui.component.wrap.DialogBottomSheet
import eu.europa.ec.rqesui.presentation.ui.component.wrap.WrapModalBottomSheet
import eu.europa.ec.rqesui.presentation.ui.component.wrap.WrapPrimaryButton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectQtspScreen(
    navController: NavController,
    viewModel: SelectQtspViewModel
) {
    val state = viewModel.viewState.value
    val context = LocalContext.current

    val isBottomSheetOpen = state.isBottomSheetOpen
    val bottomSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false
    )

    ContentScreen(
        isLoading = state.isLoading,
        navigatableAction = ScreenNavigateAction.CANCELABLE,
        onBack = { viewModel.setEvent(Event.Pop) },
        contentErrorConfig = state.error,
        bottomBar = {
            ButtonContainerBottomBar(
                onPositiveClick = {
                    viewModel.setEvent(
                        Event.PrimaryButtonPressed(documentUri = URI("uri"))
                    )
                }
            )
        }
    ) { paddingValues ->
        Content(
            state = state,
            effectFlow = viewModel.effect,
            onEventSend = { viewModel.setEvent(it) },
            onNavigationRequested = { navigationEffect ->
                when (navigationEffect) {
                    is Effect.Navigation.Finish -> context.finish()
                }
            },
            paddingValues = paddingValues,
            modalBottomSheetState = bottomSheetState
        )

        if (isBottomSheetOpen) {
            WrapModalBottomSheet(
                onDismissRequest = {
                    viewModel.setEvent(Event.BottomSheet.UpdateBottomSheetState(isOpen = false))
                },
                sheetState = bottomSheetState
            ) {
                SelectQtspSheetContent(
                    sheetContent = state.sheetContent,
                    onEventSent = { event ->
                        viewModel.setEvent(event)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Content(
    state: State,
    effectFlow: Flow<Effect>,
    onEventSend: (Event) -> Unit,
    onNavigationRequested: (Effect.Navigation) -> Unit,
    paddingValues: PaddingValues,
    modalBottomSheetState: SheetState,
) {
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        verticalArrangement = Arrangement.Top
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.Top
        ) {
            ContentTitleWithSubtitle(
                title = state.title,
                subtitle = state.subtitle,
            )

            VSpacer.Large()

            LazyColumn {
                state.options.forEach { option ->
                    item {
                        SelectionItem(
                            modifier = Modifier.wrapContentHeight(),
                            data = option,
                            onClick = {
                                onEventSend(
                                    Event.ViewDocument(URI("uriValue"))
                                )
                            }
                        )

                        VSpacer.Medium()
                    }
                }
            }
        }

    }

    LaunchedEffect(Unit) {
        effectFlow.onEach { effect ->
            when (effect) {
                is Effect.Navigation -> onNavigationRequested(effect)

                is Effect.CloseBottomSheet -> {
                    coroutineScope.launch {
                        modalBottomSheetState.hide()
                    }.invokeOnCompletion {
                        if (!modalBottomSheetState.isVisible) {
                            onEventSend(Event.BottomSheet.UpdateBottomSheetState(isOpen = false))
                        }
                    }
                }

                is Effect.ShowBottomSheet -> {
                    onEventSend(Event.BottomSheet.UpdateBottomSheetState(isOpen = true))
                }
            }
        }.collect()
    }
}

@Composable
private fun ButtonContainerBottomBar(
    onPositiveClick: () -> Unit? = {}
) {
    Column(
        modifier = Modifier
            .navigationBarsPadding()
            .fillMaxWidth()
    ) {
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.devider
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    all = SPACING_LARGE.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WrapPrimaryButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onPositiveClick.invoke() }
            ) {
                Text(
                    text = stringResource(R.string.generic_sign_button_text),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}


@Composable
private fun SelectQtspSheetContent(
    sheetContent: SelectQtspBottomSheetContent,
    onEventSent: (event: Event) -> Unit
) {
    when (sheetContent) {
        is SelectQtspBottomSheetContent.ConfirmCancellation -> {
            DialogBottomSheet(
                title = stringResource(id = R.string.sign_document_bottom_sheet_cancel_confirmation_title),
                message = stringResource(id = R.string.sign_document_bottom_sheet_cancel_confirmation_subtitle),
                positiveButtonText = stringResource(id = R.string.sign_document_bottom_sheet_continue_button_text),
                negativeButtonText = stringResource(id = R.string.sign_document_bottom_sheet_cancel_button_text),
                onPositiveClick = {
                    onEventSent(
                        Event.BottomSheet.CancelSignProcess.PrimaryButtonPressed
                    )
                },
                onNegativeClick = {
                    onEventSent(
                        Event.BottomSheet.CancelSignProcess.SecondaryButtonPressed
                    )
                }
            )
        }

        is SelectQtspBottomSheetContent.SelectQTSP -> {
            BottomSheetWithOptionsList(
                title = stringResource(
                    id = R.string.sign_document_qtsp_title
                ),
                message = stringResource(
                    id = R.string.sign_document_qtsp_subtitle
                ),
                options = sheetContent.options,
                onEventSent = onEventSent
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@ThemeModePreviews
@Composable
private fun SelectQtspScreenPreview() {
    PreviewTheme {
        Content(
            state = State(
                title = "Sign a document",
                subtitle = "Select a document from your device or scan QR",
                options = listOf(
                    SelectionItemUi(
                        title = "Document name.PDF",
                        action = "VIEW",
                    )
                )
            ),
            effectFlow = Channel<Effect>().receiveAsFlow(),
            onEventSend = {},
            onNavigationRequested = {},
            paddingValues = PaddingValues(all = SPACING_LARGE.dp),
            modalBottomSheetState = rememberModalBottomSheetState(),
        )
    }
}