import SwiftUI

struct ContentView: View {
    @State private var role: AERole?
    @State private var selectedFile: SelectedFile?
    @State private var showImporter = false
    @State private var showExporter = false
    @State private var exportURL: URL?
    @State private var gestureState: GestureState = .idle
    @State private var progress = 0.0
    @State private var status = "Connect to the AEROLINK ESP32 Wi-Fi."
    @State private var busy = false
    @State private var receiverReady = false
    @State private var connected = false
    @State private var receiverLoopTask: Task<Void, Never>?

    private let client = ESP32Client()

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                LinearGradient(
                    colors: [Color(red: 0.02, green: 0.04, blue: 0.08), Color(red: 0.08, green: 0.10, blue: 0.16)],
                    startPoint: .topLeading, endPoint: .bottomTrailing
                )
                .ignoresSafeArea()

                ScrollView(showsIndicators: false) {
                    VStack(spacing: 18) {
                        header
                        if let role {
                            mainCard(role)
                        } else {
                            rolePicker
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.vertical, 20)
                }
            }
            .navigationBarHidden(true)
        }
        .sheet(isPresented: $showImporter) {
            FileImporterView { url in
                selectedFile = SelectedFile(
                    url: url,
                    name: url.lastPathComponent,
                    size: (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                )
                showImporter = false
            }
        }
        .sheet(isPresented: $showExporter) {
            if let exportURL { FileExporterView(fileURL: exportURL) }
        }
        .task { await refreshConnection() }
        .onChange(of: gestureState) { _, state in
            guard state == .released, !busy else { return }

            if role == .sender, selectedFile != nil {
                Task { await sendFile() }
            } else if role == .receiver, receiverReady {
                gestureState = .idle
                Task { await receiveFileIfReady() }
            }
        }
        .onDisappear { receiverLoopTask?.cancel() }
    }

    private var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 3) {
                Text("AEROLINK")
                    .font(.system(size: 34, weight: .black, design: .rounded))
                    .tracking(1.2)
                    .foregroundStyle(.white)
                Text("Gesture-powered file transfer")
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.55))
            }

            Spacer()

            HStack(spacing: 7) {
                Circle()
                    .fill(connected ? Color.green : Color.orange)
                    .frame(width: 8, height: 8)
                Text(connected ? "ONLINE" : "OFFLINE")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.white.opacity(0.8))
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(.white.opacity(0.08), in: Capsule())
        }
    }

    private var rolePicker: some View {
        VStack(spacing: 16) {
            Text("What do you want this iPhone to do?")
                .font(.title3.weight(.bold))
                .foregroundStyle(.white)

            roleButton(title: "SEND A FILE", subtitle: "Pinch → move → open hand", icon: "arrow.up.circle.fill", role: .sender)
            roleButton(title: "RECEIVE A FILE", subtitle: "Open your palm to catch", icon: "arrow.down.circle.fill", role: .receiver)
        }
        .padding(20)
        .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 28))
        .overlay(RoundedRectangle(cornerRadius: 28).stroke(.white.opacity(0.09)))
    }

    private func roleButton(title: String, subtitle: String, icon: String, role: AERole) -> some View {
        Button {
            Task {
                self.role = role
                status = role == .sender ? "Sender ready. Choose a file." : "Receiver ready. Enable catch mode."
                do {
                    try await client.setRole(role)
                    connected = true
                } catch {
                    connected = false
                    status = "ESP32 not reachable. Check Wi-Fi."
                }
            }
        } label: {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.system(size: 28))
                    .frame(width: 38)

                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.headline)
                    Text(subtitle).font(.caption).foregroundStyle(.white.opacity(0.55))
                }

                Spacer()
                Image(systemName: "chevron.right")
                    .foregroundStyle(.white.opacity(0.4))
            }
            .foregroundStyle(.white)
            .padding(16)
            .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 19))
        }
    }

    private func mainCard(_ role: AERole) -> some View {
        VStack(spacing: 16) {
            cameraCard(role)
            controls(role)
            statusCard
        }
    }

    private func cameraCard(_ role: AERole) -> some View {
        ZStack(alignment: .bottom) {
            CameraView(
                gestureState: $gestureState,
                mode: role,
                enabled: !busy && (role == .sender || receiverReady)
            )
            .frame(height: 390)
            .clipShape(RoundedRectangle(cornerRadius: 28))

            LinearGradient(colors: [.clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom)
                .clipShape(RoundedRectangle(cornerRadius: 28))

            VStack(alignment: .leading, spacing: 5) {
                Text(gestureLabel)
                    .font(.headline.weight(.bold))
                    .foregroundStyle(.white)
                Text(role == .sender ? "PINCH → MOVE → OPEN HAND" : "OPEN PALM TO CATCH")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.7))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(18)
        }
        .overlay(
            RoundedRectangle(cornerRadius: 28)
                .stroke(.white.opacity(0.12), lineWidth: 1)
        )
    }

    private func controls(_ role: AERole) -> some View {
        VStack(spacing: 12) {
            if role == .sender {
                Button { showImporter = true } label: {
                    Label(selectedFile?.name ?? "CHOOSE FILE", systemImage: "doc.badge.plus")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 3)
                }
                .buttonStyle(.borderedProminent)
                .tint(.white)
                .foregroundStyle(.black)
                .disabled(busy)

                if let selectedFile {
                    HStack {
                        Image(systemName: "doc.fill")
                        Text(selectedFile.name).lineLimit(1)
                        Spacer()
                        Text(ByteCountFormatter.string(fromByteCount: Int64(selectedFile.size), countStyle: .file))
                    }
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.7))
                    .padding(.horizontal, 4)
                }
            } else {
                Button {
                    receiverReady.toggle()
                    status = receiverReady ? "Catch mode armed. Show an open palm." : "Catch mode paused."
                    if receiverReady { armReceiverLoop() } else { receiverLoopTask?.cancel() }
                } label: {
                    Label(
                        receiverReady ? "CATCH MODE ON" : "ENABLE CATCH MODE",
                        systemImage: receiverReady ? "hand.raised.fill" : "hand.raised"
                    )
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 3)
                }
                .buttonStyle(.borderedProminent)
                .tint(receiverReady ? .green : .white)
                .foregroundStyle(receiverReady ? .white : .black)
            }

            if busy {
                ProgressView(value: progress)
                    .tint(.white)
                Text("(Int(progress * 100))%")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.white.opacity(0.7))
            }
        }
        .padding(16)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 22))
    }

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("TRANSFER STATUS")
                .font(.caption.weight(.black))
                .tracking(1)
                .foregroundStyle(.white.opacity(0.4))
            Text(status)
                .font(.subheadline)
                .foregroundStyle(.white)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 20))
    }

    private var gestureLabel: String {
        switch gestureState {
        case .idle: return role == .sender ? "Ready to transfer" : (receiverReady ? "Watching for a catch" : "Camera ready")
        case .pinchLocked: return "PINCH LOCKED"
        case .moving: return "MOVE TOWARD RECEIVER"
        case .released: return "RELEASE DETECTED"
        }
    }

    private func refreshConnection() async {
        do {
            _ = try await client.status()
            await MainActor.run { connected = true }
        } catch {
            await MainActor.run { connected = false }
        }
    }

    private func sendFile() async {
        guard let selectedFile else { return }
        await MainActor.run {
            busy = true
            progress = 0
            status = "Sending (selectedFile.name)…"
        }

        do {
            try await client.setRole(.sender)
            try await client.upload(file: selectedFile, transferID: UUID().uuidString) { value in
                Task { @MainActor in progress = value }
            }
            await MainActor.run { status = "Sent successfully." }
        } catch {
            await MainActor.run { status = "Send failed: (error.localizedDescription)" }
        }

        await MainActor.run {
            busy = false
            gestureState = .idle
        }
    }

    private func receiveFileIfReady() async {
        guard receiverReady else { return }

        do {
            let s = try await client.status()
            guard s.complete == true else {
                await MainActor.run { status = "Catch detected. Waiting for the file…" }
                return
            }

            await MainActor.run {
                busy = true
                progress = 1
                status = "File received. Opening…"
            }

            let (data, name) = try await client.downloadReceivedFile()
            let safeName = name.isEmpty ? "aerolink-received.bin" : name
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(safeName)
            try data.write(to: url)

            await MainActor.run {
                exportURL = url
                showExporter = true
                status = "Received (safeName)."
                busy = false
            }
        } catch {
            await MainActor.run { status = "Receiver waiting for a complete file…" }
        }
    }

    private func armReceiverLoop() {
        receiverLoopTask?.cancel()
        receiverLoopTask = Task {
            while !Task.isCancelled {
                await receiveFileIfReady()
                try? await Task.sleep(for: .milliseconds(700))
            }
        }
    }
}
