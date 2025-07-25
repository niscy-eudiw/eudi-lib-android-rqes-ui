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

package eu.europa.ec.eudi.rqesui.domain.controller

import android.net.Uri
import androidx.core.net.toUri
import eu.europa.ec.eudi.documentretrieval.DispatchOutcome
import eu.europa.ec.eudi.documentretrieval.DocumentRetrievalConfig
import eu.europa.ec.eudi.documentretrieval.JarConfiguration
import eu.europa.ec.eudi.documentretrieval.SupportedClientIdScheme
import eu.europa.ec.eudi.rqes.AuthorizationCode
import eu.europa.ec.eudi.rqes.CSCClientConfig
import eu.europa.ec.eudi.rqes.OAuth2Client
import eu.europa.ec.eudi.rqes.core.RQESService
import eu.europa.ec.eudi.rqes.core.RQESService.Authorized
import eu.europa.ec.eudi.rqes.core.SignedDocuments
import eu.europa.ec.eudi.rqes.core.UnsignedDocument
import eu.europa.ec.eudi.rqes.core.UnsignedDocuments
import eu.europa.ec.eudi.rqes.core.documentRetrieval.DocumentRetrievalService
import eu.europa.ec.eudi.rqesui.domain.entities.error.EudiRQESUiError
import eu.europa.ec.eudi.rqesui.domain.entities.localization.LocalizableKey
import eu.europa.ec.eudi.rqesui.domain.extension.toShareableUri
import eu.europa.ec.eudi.rqesui.domain.extension.toUriOrEmpty
import eu.europa.ec.eudi.rqesui.domain.helper.FileHelper.uriToFile
import eu.europa.ec.eudi.rqesui.domain.util.safeLet
import eu.europa.ec.eudi.rqesui.infrastructure.EudiRQESUi
import eu.europa.ec.eudi.rqesui.infrastructure.config.data.CertificateData
import eu.europa.ec.eudi.rqesui.infrastructure.config.data.DocumentData
import eu.europa.ec.eudi.rqesui.infrastructure.config.data.QtspData
import eu.europa.ec.eudi.rqesui.infrastructure.config.data.toCertificatesData
import eu.europa.ec.eudi.rqesui.infrastructure.provider.ResourceProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface RqesController {

    fun getSelectedFile(): EudiRqesGetSelectedFilePartialState

    suspend fun getRemoteOrLocalFile(): EudiRqesGetSelectedFilePartialState

    fun getQtsps(): EudiRqesGetQtspsPartialState

    fun setSelectedQtsp(qtspData: QtspData): EudiRqesSetSelectedQtspPartialState

    fun getSelectedQtsp(): EudiRqesGetSelectedQtspPartialState

    suspend fun getServiceAuthorizationUrl(rqesService: RQESService): EudiRqesGetServiceAuthorizationUrlPartialState

    suspend fun authorizeService(): EudiRqesAuthorizeServicePartialState

    fun setAuthorizedService(authorizedService: Authorized)

    fun getAuthorizedService(): Authorized?

    suspend fun getAvailableCertificates(authorizedService: Authorized): EudiRqesGetCertificatesPartialState

    suspend fun getCredentialAuthorizationUrl(
        authorizedService: Authorized,
        certificateData: CertificateData,
    ): EudiRqesGetCredentialAuthorizationUrlPartialState

    suspend fun authorizeCredential(): EudiRqesAuthorizeCredentialPartialState

    suspend fun signDocuments(authorizedCredential: RQESService.CredentialAuthorized): EudiRqesSignDocumentsPartialState

    suspend fun saveSignedDocuments(
        originalDocumentName: String,
        signedDocuments: SignedDocuments,
    ): EudiRqesSaveSignedDocumentsPartialState
}

internal class RqesControllerImpl(
    private val eudiRQESUi: EudiRQESUi,
    private val resourceProvider: ResourceProvider,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : RqesController {

    private val genericErrorMsg
        get() = resourceProvider.genericErrorMessage()

    private val genericErrorTitle
        get() = resourceProvider.genericErrorTitle()

    private val genericServiceErrorMsg
        get() = resourceProvider.genericServiceErrorMessage()

    override fun getSelectedFile(): EudiRqesGetSelectedFilePartialState {
        return runCatching {
            eudiRQESUi.getSessionData().file?.let { safeSelectedFile ->
                EudiRqesGetSelectedFilePartialState.Success(
                    file = safeSelectedFile
                )
            } ?: EudiRqesGetSelectedFilePartialState.Failure(
                error = EudiRQESUiError(
                    title = genericErrorTitle,
                    message = resourceProvider.getLocalizedString(
                        LocalizableKey.GenericErrorDocumentNotFound
                    )
                )
            )
        }.getOrElse {
            EudiRqesGetSelectedFilePartialState.Failure(
                error = EudiRQESUiError(
                    title = genericErrorTitle,
                    message = it.localizedMessage ?: genericErrorMsg
                )
            )
        }
    }

    override suspend fun getRemoteOrLocalFile(): EudiRqesGetSelectedFilePartialState {
        return withContext(dispatcher) {
            runCatching {

                eudiRQESUi.getSessionData().file?.let {
                    return@runCatching getSelectedFile()
                }

                eudiRQESUi.getSessionData().remoteUrl?.let {

                    val documentRetrievalConfig = eudiRQESUi
                        .getEudiRQESUiConfig()
                        .documentRetrievalConfig

                    val supportedClientIdSchemes: List<SupportedClientIdScheme> =
                        documentRetrievalConfig.impl?.let {
                            listOf(
                                SupportedClientIdScheme.X509SanUri(it),
                                SupportedClientIdScheme.X509SanDns(it)
                            )
                        } ?: listOf(
                            SupportedClientIdScheme.X509SanUri { true },
                            SupportedClientIdScheme.X509SanDns { true }
                        )

                    val documentRetrievalService = DocumentRetrievalService(
                        downloadTempDir = resourceProvider.getDownloadsCache(),
                        config = DocumentRetrievalConfig(
                            jarConfiguration = JarConfiguration.Default,
                            supportedClientIdSchemes = supportedClientIdSchemes
                        )
                    )

                    val resolutionOutcome = documentRetrievalService
                        .resolveDocument(it)
                        .getOrThrow()

                    val resolvedDocuments = resolutionOutcome.resolvedDocuments

                    if (resolvedDocuments.isEmpty()) {
                        return@let
                    }

                    if (resolvedDocuments.size > 1) {
                        return@runCatching EudiRqesGetSelectedFilePartialState.Failure(
                            error = EudiRQESUiError(
                                title = genericErrorTitle,
                                message = resourceProvider.getLocalizedString(
                                    LocalizableKey.GenericErrorDocumentMultipleNotSupported
                                )
                            )
                        )
                    }

                    val document = resolvedDocuments.first().let {
                        DocumentData(
                            documentName = it.file.name,
                            uri = Uri.fromFile(it.file)
                        )
                    }

                    eudiRQESUi.setRemoteResolutionOutcome(
                        resolutionOutcome
                    )
                    eudiRQESUi.setSessionData(
                        eudiRQESUi.getSessionData().copy(file = document)
                    )

                    return@runCatching EudiRqesGetSelectedFilePartialState.Success(
                        file = document
                    )
                }

                return@runCatching EudiRqesGetSelectedFilePartialState.Failure(
                    error = EudiRQESUiError(
                        title = genericErrorTitle,
                        message = resourceProvider.getLocalizedString(
                            LocalizableKey.GenericErrorDocumentNotFound
                        )
                    )
                )

            }.getOrElse {
                EudiRqesGetSelectedFilePartialState.Failure(
                    error = EudiRQESUiError(
                        title = genericErrorTitle,
                        message = it.localizedMessage ?: genericErrorMsg
                    )
                )
            }
        }
    }

    override fun getQtsps(): EudiRqesGetQtspsPartialState {
        return runCatching {
            EudiRqesGetQtspsPartialState.Success(qtsps = eudiRQESUi.getEudiRQESUiConfig().qtsps)
        }.getOrElse {
            EudiRqesGetQtspsPartialState.Failure(
                error = EudiRQESUiError(
                    title = genericErrorTitle,
                    message = it.localizedMessage ?: genericErrorMsg
                )
            )
        }
    }

    override fun setSelectedQtsp(qtspData: QtspData): EudiRqesSetSelectedQtspPartialState {
        return runCatching {

            eudiRQESUi.setSessionData(
                eudiRQESUi.getSessionData().copy(
                    qtsp = qtspData
                )
            )

            when (val result = createRqesService(qtspData)) {
                is EudiRqesCreateServicePartialState.Failure -> {
                    EudiRqesSetSelectedQtspPartialState.Failure(error = result.error)
                }

                is EudiRqesCreateServicePartialState.Success -> {
                    EudiRqesSetSelectedQtspPartialState.Success(service = result.service)
                }
            }
        }.getOrElse {
            EudiRqesSetSelectedQtspPartialState.Failure(
                error = EudiRQESUiError(
                    title = genericErrorTitle,
                    message = it.localizedMessage ?: genericErrorMsg
                )
            )
        }
    }

    override fun getSelectedQtsp(): EudiRqesGetSelectedQtspPartialState {
        return runCatching {
            val selectedQtsp = eudiRQESUi.getSessionData().qtsp
            selectedQtsp?.let { safeSelectedQtsp ->
                EudiRqesGetSelectedQtspPartialState.Success(qtsp = safeSelectedQtsp)
            } ?: EudiRqesGetSelectedQtspPartialState.Failure(
                error = EudiRQESUiError(
                    title = genericErrorTitle,
                    message = resourceProvider.getLocalizedString(LocalizableKey.GenericErrorQtspNotFound)
                )
            )
        }.getOrElse {
            EudiRqesGetSelectedQtspPartialState.Failure(
                error = EudiRQESUiError(
                    title = genericErrorTitle,
                    message = it.localizedMessage ?: genericErrorMsg
                )
            )
        }
    }

    override suspend fun getServiceAuthorizationUrl(rqesService: RQESService): EudiRqesGetServiceAuthorizationUrlPartialState {
        return withContext(dispatcher) {
            runCatching {
                val authorizationUrl = rqesService.getServiceAuthorizationUrl()
                    .getOrThrow()
                    .value.toString().toUriOrEmpty()
                EudiRqesGetServiceAuthorizationUrlPartialState.Success(authorizationUrl = authorizationUrl)
            }.getOrElse {
                EudiRqesGetServiceAuthorizationUrlPartialState.Failure(
                    error = EudiRQESUiError(
                        title = genericErrorTitle,
                        message = genericServiceErrorMsg
                    )
                )
            }
        }
    }

    override suspend fun authorizeService(): EudiRqesAuthorizeServicePartialState {
        return withContext(dispatcher) {
            runCatching {
                safeLet(
                    eudiRQESUi.getRqesService(),
                    eudiRQESUi.getSessionData().authorizationCode
                ) { safeService, safeAuthorizationCode ->
                    val authorizedService = safeService.authorizeService(
                        authorizationCode = AuthorizationCode(safeAuthorizationCode)
                    ).getOrThrow()

                    EudiRqesAuthorizeServicePartialState.Success(authorizedService = authorizedService)
                } ?: EudiRqesAuthorizeServicePartialState.Failure(
                    error = EudiRQESUiError(
                        title = genericErrorTitle,
                        message = genericErrorMsg
                    )
                )
            }.getOrElse {
                EudiRqesAuthorizeServicePartialState.Failure(
                    error = EudiRQESUiError(
                        title = genericErrorTitle,
                        message = genericServiceErrorMsg
                    )
                )
            }
        }
    }

    override fun setAuthorizedService(authorizedService: Authorized) {
        eudiRQESUi.setAuthorizedService(authorizedService)
    }

    override fun getAuthorizedService(): Authorized? {
        return eudiRQESUi.getAuthorizedService()
    }

    override suspend fun getAvailableCertificates(authorizedService: Authorized): EudiRqesGetCertificatesPartialState {
        return withContext(dispatcher) {
            runCatching {
                val certificates = authorizedService.listCredentials()
                    .getOrThrow()
                    .toCertificatesData(
                        createDefaultName = { certificateIndex: Int ->
                            resourceProvider.getLocalizedString(
                                localizableKey = LocalizableKey.Certificate,
                                args = listOf((certificateIndex + 1).toString())
                            )
                        }
                    )
                if (certificates.isNotEmpty()) {
                    EudiRqesGetCertificatesPartialState.Success(certificates = certificates)
                } else {
                    EudiRqesGetCertificatesPartialState.Failure(
                        error = EudiRQESUiError(
                            title = genericErrorTitle,
                            message = resourceProvider.getLocalizedString(LocalizableKey.GenericErrorCertificatesNotFound)
                        )
                    )
                }
            }.getOrElse {
                EudiRqesGetCertificatesPartialState.Failure(
                    error = EudiRQESUiError(
                        title = genericErrorTitle,
                        message = genericServiceErrorMsg
                    )
                )
            }
        }
    }

    override suspend fun getCredentialAuthorizationUrl(
        authorizedService: Authorized,
        certificateData: CertificateData
    ): EudiRqesGetCredentialAuthorizationUrlPartialState {
        return withContext(dispatcher) {
            runCatching {
                eudiRQESUi.getSessionData().file?.let { safeSelectedFile ->

                    val fileToBeSigned = uriToFile(
                        context = resourceProvider.provideContext(),
                        uri = safeSelectedFile.uri,
                        fileName = safeSelectedFile.documentName
                    ).getOrThrow()

                    // Prepare the documents to sign
                    val unsignedDocuments = UnsignedDocuments(
                        UnsignedDocument(
                            label = safeSelectedFile.documentName,
                            file = fileToBeSigned,
                        )
                    )

                    val authorizationUrl = authorizedService
                        .getCredentialAuthorizationUrl(
                            credential = certificateData.certificate,
                            documents = unsignedDocuments,
                        ).getOrThrow()
                        .value.toString().toUriOrEmpty()

                    EudiRqesGetCredentialAuthorizationUrlPartialState.Success(authorizationUrl = authorizationUrl)
                } ?: EudiRqesGetCredentialAuthorizationUrlPartialState.Failure(
                    error = EudiRQESUiError(
                        title = genericErrorTitle,
                        message = resourceProvider.getLocalizedString(
                            LocalizableKey.GenericErrorDocumentNotFound
                        )
                    )
                )
            }.getOrElse {
                EudiRqesGetCredentialAuthorizationUrlPartialState.Failure(
                    error = EudiRQESUiError(
                        title = genericErrorTitle,
                        message = genericServiceErrorMsg
                    )
                )
            }
        }
    }

    override suspend fun authorizeCredential(): EudiRqesAuthorizeCredentialPartialState {
        return withContext(dispatcher) {
            runCatching {
                safeLet(
                    getAuthorizedService(),
                    eudiRQESUi.getSessionData().authorizationCode
                ) { safeAuthorizedService, safeAuthorizationCode ->
                    val authorizedCredential: RQESService.CredentialAuthorized =
                        safeAuthorizedService.authorizeCredential(
                            authorizationCode = AuthorizationCode(safeAuthorizationCode)
                        ).getOrThrow()

                    EudiRqesAuthorizeCredentialPartialState.Success(authorizedCredential = authorizedCredential)
                } ?: EudiRqesAuthorizeCredentialPartialState.Failure(
                    error = EudiRQESUiError(
                        title = genericErrorTitle,
                        message = genericErrorMsg
                    )
                )
            }.getOrElse {
                EudiRqesAuthorizeCredentialPartialState.Failure(
                    error = EudiRQESUiError(
                        title = genericErrorTitle,
                        message = genericServiceErrorMsg
                    )
                )
            }
        }
    }

    override suspend fun signDocuments(authorizedCredential: RQESService.CredentialAuthorized): EudiRqesSignDocumentsPartialState {
        return withContext(dispatcher) {
            runCatching {
                val signedDocuments = authorizedCredential.signDocuments().getOrThrow()
                EudiRqesSignDocumentsPartialState.Success(signedDocuments = signedDocuments)
            }.getOrElse {
                EudiRqesSignDocumentsPartialState.Failure(
                    error = EudiRQESUiError(
                        title = genericErrorTitle,
                        message = genericServiceErrorMsg
                    )
                )
            }
        }
    }

    override suspend fun saveSignedDocuments(
        originalDocumentName: String,
        signedDocuments: SignedDocuments,
    ): EudiRqesSaveSignedDocumentsPartialState {
        return withContext(dispatcher) {
            runCatching {

                val savedDocuments: Map<String, Uri> = buildMap {
                    signedDocuments.forEach {
                        put(
                            it.key,
                            it.value
                                .toShareableUri(resourceProvider.provideContext())
                                .getOrThrow()
                        )
                    }
                }

                if (savedDocuments.isNotEmpty()) {
                    eudiRQESUi.getRemoteResolutionOutcome()?.let {
                        when (val outcome = it.dispatch(signedDocuments)) {
                            is DispatchOutcome.Accepted -> {
                                return@runCatching EudiRqesSaveSignedDocumentsPartialState.Success(
                                    savedDocuments = savedDocuments,
                                    isRemote = true,
                                    redirectUri = outcome.redirectURI?.toString()?.toUri()
                                )
                            }

                            DispatchOutcome.Rejected -> {
                                return@runCatching EudiRqesSaveSignedDocumentsPartialState.Failure(
                                    error = EudiRQESUiError(
                                        title = genericErrorTitle,
                                        message = genericErrorMsg
                                    )
                                )
                            }
                        }
                    }

                    EudiRqesSaveSignedDocumentsPartialState.Success(
                        savedDocuments = savedDocuments,
                        isRemote = false,
                        redirectUri = null
                    )
                } else {
                    EudiRqesSaveSignedDocumentsPartialState.Failure(
                        error = EudiRQESUiError(
                            title = genericErrorTitle,
                            message = genericErrorMsg
                        )
                    )
                }
            }.getOrElse {
                EudiRqesSaveSignedDocumentsPartialState.Failure(
                    error = EudiRQESUiError(
                        title = genericErrorTitle,
                        message = it.localizedMessage ?: genericErrorMsg
                    )
                )
            }
        }
    }

    private fun createRqesService(qtspData: QtspData): EudiRqesCreateServicePartialState {
        return runCatching {
            val service = RQESService(
                serviceEndpointUrl = qtspData.endpoint.toString(),
                config = CSCClientConfig(
                    client = OAuth2Client.Confidential.ClientSecretBasic(
                        clientId = qtspData.clientId,
                        clientSecret = qtspData.clientSecret
                    ),
                    authFlowRedirectionURI = qtspData.authFlowRedirectionURI,
                    tsaurl = qtspData.tsaUrl,
                ),
                outputPathDir = resourceProvider.getSignedDocumentsCache().absolutePath,
                hashAlgorithm = qtspData.hashAlgorithm
            )
            eudiRQESUi.setRqesService(service)
            EudiRqesCreateServicePartialState.Success(service = service)
        }.getOrElse {
            EudiRqesCreateServicePartialState.Failure(
                error = EudiRQESUiError(
                    title = genericErrorTitle,
                    message = it.localizedMessage ?: genericErrorMsg
                )
            )
        }
    }
}

internal sealed class EudiRqesGetSelectedFilePartialState {
    data class Success(val file: DocumentData) : EudiRqesGetSelectedFilePartialState()
    data class Failure(val error: EudiRQESUiError) : EudiRqesGetSelectedFilePartialState()
}

internal sealed class EudiRqesGetQtspsPartialState {
    data class Success(val qtsps: List<QtspData>) : EudiRqesGetQtspsPartialState()
    data class Failure(val error: EudiRQESUiError) : EudiRqesGetQtspsPartialState()
}

internal sealed class EudiRqesSetSelectedQtspPartialState {
    data class Success(val service: RQESService) : EudiRqesSetSelectedQtspPartialState()
    data class Failure(val error: EudiRQESUiError) : EudiRqesSetSelectedQtspPartialState()
}

internal sealed class EudiRqesGetSelectedQtspPartialState {
    data class Success(val qtsp: QtspData) : EudiRqesGetSelectedQtspPartialState()
    data class Failure(val error: EudiRQESUiError) : EudiRqesGetSelectedQtspPartialState()
}

internal sealed class EudiRqesGetServiceAuthorizationUrlPartialState {
    data class Success(val authorizationUrl: Uri) : EudiRqesGetServiceAuthorizationUrlPartialState()
    data class Failure(
        val error: EudiRQESUiError
    ) : EudiRqesGetServiceAuthorizationUrlPartialState()
}

internal sealed class EudiRqesAuthorizeServicePartialState {
    data class Success(val authorizedService: Authorized) : EudiRqesAuthorizeServicePartialState()
    data class Failure(val error: EudiRQESUiError) : EudiRqesAuthorizeServicePartialState()
}

internal sealed class EudiRqesCreateServicePartialState {
    data class Success(val service: RQESService) : EudiRqesCreateServicePartialState()
    data class Failure(val error: EudiRQESUiError) : EudiRqesCreateServicePartialState()
}

internal sealed class EudiRqesGetCertificatesPartialState {
    data class Success(
        val certificates: List<CertificateData>,
    ) : EudiRqesGetCertificatesPartialState()

    data class Failure(val error: EudiRQESUiError) : EudiRqesGetCertificatesPartialState()
}

internal sealed class EudiRqesGetCredentialAuthorizationUrlPartialState {
    data class Success(
        val authorizationUrl: Uri,
    ) : EudiRqesGetCredentialAuthorizationUrlPartialState()

    data class Failure(
        val error: EudiRQESUiError
    ) : EudiRqesGetCredentialAuthorizationUrlPartialState()
}

internal sealed class EudiRqesAuthorizeCredentialPartialState {
    data class Success(
        val authorizedCredential: RQESService.CredentialAuthorized,
    ) : EudiRqesAuthorizeCredentialPartialState()

    data class Failure(val error: EudiRQESUiError) : EudiRqesAuthorizeCredentialPartialState()
}

internal sealed class EudiRqesSignDocumentsPartialState {
    data class Success(val signedDocuments: SignedDocuments) : EudiRqesSignDocumentsPartialState()
    data class Failure(val error: EudiRQESUiError) : EudiRqesSignDocumentsPartialState()
}

internal sealed class EudiRqesSaveSignedDocumentsPartialState {
    data class Success(
        val savedDocuments: Map<String, Uri>,
        val isRemote: Boolean,
        val redirectUri: Uri?
    ) :
        EudiRqesSaveSignedDocumentsPartialState()

    data class Failure(val error: EudiRQESUiError) : EudiRqesSaveSignedDocumentsPartialState()
}