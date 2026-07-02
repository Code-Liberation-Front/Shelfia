import AuthenticationServices
import SwiftUI

@main
struct ShelfieApp: App {
    @StateObject private var state = AppState.shared

    var body: some Scene {
        WindowGroup {
            Group {
                if state.loggedIn {
                    MainView()
                } else {
                    LoginView()
                }
            }
            .environmentObject(state)
            .tint(Color(red: 0.988, green: 0.494, blue: 0.059)) // Shelfia orange
            .preferredColorScheme(.dark)
        }
    }
}

// MARK: - Two-step login (server → auth methods), matching Android

struct LoginView: View {
    @EnvironmentObject var state: AppState

    @State private var server = ""
    @State private var serverStatus: ServerStatus?
    @State private var username = ""
    @State private var password = ""
    @State private var error: String?
    @State private var busy = false
    @State private var pendingOidc: AbsClient.PendingOidc?
    @State private var webSession: ASWebAuthenticationSession?
    @State private var presentationProvider = WebAuthPresentationProvider()

    var body: some View {
        VStack(spacing: 16) {
            Text("Shelfia").font(.largeTitle.bold())
            Text("Sign in to your Audiobookshelf server")
                .foregroundStyle(.secondary)

            if serverStatus == nil {
                serverStep
            } else {
                authStep
            }

            if let error {
                Text(error).foregroundStyle(.red).font(.footnote)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(24)
        .frame(maxWidth: 480)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var serverStep: some View {
        VStack(spacing: 16) {
            TextField("Server address", text: $server)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .textFieldStyle(.roundedBorder)
            Button {
                run {
                    serverStatus = try await state.client.status(server: server)
                }
            } label: {
                if busy { ProgressView() } else { Text("Continue").frame(maxWidth: .infinity) }
            }
            .buttonStyle(.borderedProminent)
            .disabled(busy || server.isEmpty)
        }
    }

    @ViewBuilder
    private var authStep: some View {
        let status = serverStatus ?? ServerStatus()
        VStack(spacing: 16) {
            Text(AbsClient.normalize(server))
                .font(.footnote)
                .foregroundStyle(.secondary)

            if let message = status.authFormData?.authLoginCustomMessage, !message.isEmpty {
                Text(stripHtml(message)).font(.footnote).foregroundStyle(.secondary)
            }

            if status.supportsOpenId {
                Button {
                    startSso()
                } label: {
                    if busy {
                        ProgressView()
                    } else {
                        Text(status.authFormData?.authOpenIDButtonText ?? "Login with OpenId")
                            .frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(busy)
            }

            if status.supportsOpenId && status.supportsLocal {
                HStack {
                    VStack { Divider() }
                    Text("or sign in with a password")
                        .font(.caption).foregroundStyle(.secondary)
                        .fixedSize()
                    VStack { Divider() }
                }
            }

            if status.supportsLocal {
                TextField("Username", text: $username)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textFieldStyle(.roundedBorder)
                SecureField("Password", text: $password)
                    .textFieldStyle(.roundedBorder)
                let signIn = Button {
                    run {
                        try await state.login(server: server, username: username, password: password)
                        await state.refresh()
                    }
                } label: {
                    if busy { ProgressView() } else { Text("Sign in").frame(maxWidth: .infinity) }
                }
                .disabled(busy || username.isEmpty)
                if status.supportsOpenId {
                    signIn.buttonStyle(.bordered)
                } else {
                    signIn.buttonStyle(.borderedProminent)
                }
            }

            Button("Use a different server") {
                serverStatus = nil
                error = nil
                username = ""
                password = ""
            }
            .font(.footnote)
        }
    }

    private func run(_ work: @escaping () async throws -> Void) {
        busy = true
        error = nil
        Task {
            do { try await work() } catch { self.error = error.localizedDescription }
            busy = false
        }
    }

    /**
     OIDC per the Audiobookshelf mobile contract: the app fetches the IdP URL
     (capturing state cookies), authenticates in a web session, then completes
     with the code on /auth/openid/callback replaying those cookies.
     */
    private func startSso() {
        run {
            let (idpUrl, pending) = try await state.client.startOidc(server: server)
            pendingOidc = pending
            let session = ASWebAuthenticationSession(
                url: idpUrl,
                callbackURLScheme: "audiobookshelf"
            ) { callbackUrl, sessionError in
                Task { @MainActor in
                    defer { webSession = nil }
                    guard let callbackUrl else {
                        if let sessionError, (sessionError as? ASWebAuthenticationSessionError)?.code != .canceledLogin {
                            error = sessionError.localizedDescription
                        }
                        return
                    }
                    let components = URLComponents(url: callbackUrl, resolvingAgainstBaseURL: false)
                    let code = components?.queryItems?.first { $0.name == "code" }?.value
                    let oauthState = components?.queryItems?.first { $0.name == "state" }?.value
                    guard let code, let oauthState, let pending = pendingOidc else {
                        error = "Sign-in response was missing the authorization code."
                        return
                    }
                    run {
                        try await state.completeOidcLogin(code: code, state: oauthState, pending: pending)
                        await state.refresh()
                    }
                }
            }
            session.presentationContextProvider = presentationProvider
            session.prefersEphemeralWebBrowserSession = false
            webSession = session
            session.start()
        }
    }
}

final class WebAuthPresentationProvider: NSObject, ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first ?? ASPresentationAnchor()
    }
}
