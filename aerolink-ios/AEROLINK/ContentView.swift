import SwiftUI

struct ContentView: View {
    @State private var role: AERole?
    @State private var selectedFile: SelectedFile?
    @State private var showImporter = false
    @State private var showExporter = false
    @State private var exportURL: URL?
    @State private var gestureState: GestureState = .idle
    @State private var progress = 0.0
    @State private var status = "Connect to an AEROLINK ESP32 Wi-Fi network."
    @State private var busy = false
    @State private var receiverReady = false

    private let client = ESP32Client()

    var body: some View {
        NavigationStack {
            ZStack {
                LinearGradient(colors: [.black, Color(red: 0.07, green: 0.09, blue: 0.15)], startPoint: .top, endPoint: .bottom)
                    .ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 18) {
                        header

                        if role == nil {
                            rolePicker
                        } else {
                            cameraCard
                            controls
                            statusCard
                        }
                    }
                    .padding()
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
            if let exportURL {
                FileExporterView(fileURL: exportURL)
            }
        }
        .onChange(of: gestureState) { _, state in
            if state == .released && role == .sender && selectedFile != nil && !busy {
                Task { await sendFile() }
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text("AEROLINK")
                .font(.system(size: 36, weight: .black, design: .rounded))
                .foregroundStyle(.white)
            Text("Air-transfer interface")
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var rolePicker: some View {
        VStack(spacing: 14) {
            Text("Choose this phone's role")
                .font(.headline)
                .foregroundStyle(.white)

            Button {
                role = .sender
                status = "Sender ready. Choose a file."
            } label: {
                Label("SEND", systemImage: "arrow.up.circle.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)

            Button {
                role = .receiver
                status = "Receiver ready. Show an open palm."
            } label: {
                Label("RECEIVE", systemImage: "arrow.down.circle.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
        .padding(24)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 24))
    }

    private var cameraCard: some View {
        ZStack(alignment: .bottom) {
            CameraView(gestureState: $gestureState, enabled: !busy)
                .frame(height: 360)
                .clipShape(RoundedRectangle(cornerRadius: 24))

            VStack(alignment: .leading, spacing: 5) {
                Text(gestureLabel)
                    .font(.headline)
                    .foregroundStyle(.white)
                Text(role == .sender ? "Pinch → move → open hand" : "Open palm to catch")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.85))
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.black.opacity(0.55))
        }
    }

    private var controls: some View {
        VStack(spacing: 12) {
            if role == .sender {
                Button {
                    showImporter = true
                } label: {
                    Label(selectedFile?.name ?? "CHOOSE FILE", systemImage: "doc.badge.plus")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(busy)

                if let selectedFile {
                    Text("\(selectedFile.name) • \(ByteCountFormatter.string(fromByteCount: Int64(selectedFile.size), countStyle: .file))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } else {
                Button {
                    receiverReady.toggle()
                    status = receiverReady ? "Catch mode armed." : "Catch mode paused."
                } label: {
                    Label(receiverReady ? "CATCH MODE ON" : "ENABLE CATCH MODE", systemImage: receiverReady ? "hand.raised.fill" : "hand.raised")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
            }

            if busy {
                ProgressView(value: progress)
                    .tint(.white)
                Text("\(Int(progress * 100))%")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.white)
            }
        }
    }

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("STATUS")
                .font(.caption.weight(.bold))
                .foregroundStyle(.secondary)
            Text(status)
                .foregroundStyle(.white)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private var gestureLabel: String {
        switch gestureState {
        case .idle: return role == .sender ? "Ready" : (receiverReady ? "Watching for catch" : "Camera ready")
        case .pinchLocked: return "PINCH LOCKED"
        case .moving: return "MOVE…"
        case .released: return "RELEASE"
        }
    }

    private func sendFile() async {
        guard let selectedFile else { return }
        busy = true
        progress = 0
        status = "Sending \(selectedFile.name)…"

        do {
            try await client.upload(file: selectedFile, transferID: UUID().uuidString, progress: { value in
                Task { @MainActor in progress = value }
            })
            status = "Sent successfully."
        } catch {
            status = "Send failed: \(error.localizedDescription)"
        }

        busy = false
        gestureState = .idle
    }

    private func pollReceiver() async {
        guard receiverReady, role == .receiver, !busy else { return }
        do {
            let s = try await client.status()
            if s.complete == true {
                busy = true
                status = "File received. Downloading…"
                let (data, name) = try await client.downloadReceivedFile()
                let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
                try data.write(to: url)
                exportURL = url
                showExporter = true
                status = "Received \(name)."
                busy = false
            }
        } catch {
            status = "Receiver waiting…"
        }
    }

    private func armReceiverLoop() {
        Task {
            while receiverReady && role == .receiver {
                await pollReceiver()
                try? await Task.sleep(for: .milliseconds(700))
            }
        }
    }
}
