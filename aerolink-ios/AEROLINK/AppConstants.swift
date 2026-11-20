import Foundation

enum AppConstants {
    static let esp32BaseURL = URL(string: "http://192.168.4.1")!
    static let chunkSize = 1024
    static let pollInterval: TimeInterval = 0.7
}
