import Foundation

enum AERole: String {
    case sender
    case receiver
}

struct ESPStatus: Decodable {
    let role: String?
    let clients: Int?
    let uart: Bool?
    let packets: Int?
    let total: Int?
    let receiving: Bool?
    let complete: Bool?
    let bytes: Int?
    let name: String?
}

struct SelectedFile {
    let url: URL
    let name: String
    let size: Int
}

enum GestureState: Equatable {
    case idle
    case pinchLocked
    case moving
    case released
}
