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
            .tint(Color(red: 0.988, green: 0.494, blue: 0.059)) // Overcast orange
            .preferredColorScheme(.dark)
        }
    }
}

struct LoginView: View {
    @EnvironmentObject var state: AppState
    @State private var server = ""
    @State private var username = ""
    @State private var password = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        VStack(spacing: 16) {
            Text("Shelfia").font(.largeTitle.bold())
            Text("Sign in to your Audiobookshelf server")
                .foregroundStyle(.secondary)
            TextField("Server address", text: $server)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .textFieldStyle(.roundedBorder)
            TextField("Username", text: $username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textFieldStyle(.roundedBorder)
            SecureField("Password", text: $password)
                .textFieldStyle(.roundedBorder)
            if let error {
                Text(error).foregroundStyle(.red).font(.footnote)
            }
            Button {
                busy = true
                error = nil
                Task {
                    do {
                        try await state.login(server: server, username: username, password: password)
                        await state.refresh()
                    } catch {
                        self.error = error.localizedDescription
                    }
                    busy = false
                }
            } label: {
                if busy { ProgressView() } else { Text("Sign in").frame(maxWidth: .infinity) }
            }
            .buttonStyle(.borderedProminent)
            .disabled(busy || server.isEmpty || username.isEmpty)
        }
        .padding(24)
    }
}
