import Foundation

final class ESP32Client {
    private let baseURL = AppConstants.esp32BaseURL

    func status() async throws -> ESPStatus {
        let (data, response) = try await URLSession.shared.data(from: baseURL.appendingPathComponent("api/status"))
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return try JSONDecoder().decode(ESPStatus.self, from: data)
    }

    func upload(file: SelectedFile, transferID: String, progress: @escaping (Double) -> Void) async throws {
        let fileHandle = try FileHandle(forReadingFrom: file.url)
        defer { try? fileHandle.close() }

        let totalPackets = max(1, Int(ceil(Double(file.size) / Double(AppConstants.chunkSize))))

        for sequence in 0..<totalPackets {
            try Task.checkCancellation()

            let data = try fileHandle.read(upToCount: AppConstants.chunkSize) ?? Data()
            let encodedID = transferID.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? transferID
            let encodedName = file.name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "aerolink-file.bin"

            var components = URLComponents(url: baseURL.appendingPathComponent("api/chunk"), resolvingAgainstBaseURL: false)!
            components.queryItems = [
                URLQueryItem(name: "id", value: encodedID),
                URLQueryItem(name: "name", value: encodedName),
                URLQueryItem(name: "seq", value: String(sequence)),
                URLQueryItem(name: "total", value: String(totalPackets))
            ]

            var request = URLRequest(url: components.url!)
            request.httpMethod = "POST"
            request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            request.httpBody = data

            let (_, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                throw URLError(.cannotParseResponse)
            }

            progress(Double(sequence + 1) / Double(totalPackets))
        }
    }

    func downloadReceivedFile() async throws -> (Data, String) {
        let (data, response) = try await URLSession.shared.data(from: baseURL.appendingPathComponent("api/file"))
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }

        let name = filename(from: http.value(forHTTPHeaderField: "Content-Disposition")) ?? "aerolink-received.bin"
        return (data, name)
    }

    private func filename(from contentDisposition: String?) -> String? {
        guard let value = contentDisposition else { return nil }
        let marker = "filename=""
        guard let start = value.range(of: marker)?.upperBound,
              let end = value[start...].firstIndex(of: """) else { return nil }
        return String(value[start..<end])
    }
}
