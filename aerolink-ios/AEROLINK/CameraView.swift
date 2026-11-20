import SwiftUI
import AVFoundation
import Vision

struct CameraView: UIViewRepresentable {
    @Binding var gestureState: GestureState
    let mode: AERole
    let enabled: Bool

    func makeCoordinator() -> Coordinator {
        Coordinator(gestureState: $gestureState, mode: mode)
    }

    func makeUIView(context: Context) -> UIView {
        let view = PreviewView()
        context.coordinator.setup(view: view)
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.enabled = enabled
        context.coordinator.mode = mode
    }

    final class Coordinator: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
        @Binding var gestureState: GestureState
        var mode: AERole
        var enabled = true

        private let session = AVCaptureSession()
        private let queue = DispatchQueue(label: "aerolink.camera.frames")
        private let visionQueue = DispatchQueue(label: "aerolink.vision")
        private let handRequest = VNDetectHumanHandPoseRequest()
        private var pinchFrames = 0
        private var movementFrames = 0
        private var pinchOrigin: CGPoint?
        private var releaseCooldown = 0

        init(gestureState: Binding<GestureState>, mode: AERole) {
            _gestureState = gestureState
            self.mode = mode
        }

        func setup(view: PreviewView) {
            guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else {
                AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                    if granted { DispatchQueue.main.async { self?.start(view: view) } }
                }
                return
            }
            start(view: view)
        }

        private func start(view: PreviewView) {
            queue.async { [weak self] in
                guard let self else { return }
                session.beginConfiguration()
                session.sessionPreset = .medium

                guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front),
                      let input = try? AVCaptureDeviceInput(device: camera),
                      session.canAddInput(input) else {
                    session.commitConfiguration()
                    return
                }

                session.addInput(input)

                let output = AVCaptureVideoDataOutput()
                output.alwaysDiscardsLateVideoFrames = true
                output.setSampleBufferDelegate(self, queue: visionQueue)
                guard session.canAddOutput(output) else {
                    session.commitConfiguration()
                    return
                }

                session.addOutput(output)
                output.connection(with: .video)?.videoOrientation = .portrait
                session.commitConfiguration()

                DispatchQueue.main.async {
                    view.videoPreviewLayer.session = self.session
                    view.videoPreviewLayer.videoGravity = .resizeAspectFill
                }
                session.startRunning()
            }
        }

        func stop() {
            queue.async { [weak self] in self?.session.stopRunning() }
        }

        func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
            guard enabled, releaseCooldown == 0 else {
                if releaseCooldown > 0 { releaseCooldown -= 1 }
                return
            }

            let handler = VNImageRequestHandler(cmSampleBuffer: sampleBuffer, orientation: .leftMirrored, options: [:])

            do {
                try handler.perform([handRequest])
                guard let observation = handRequest.results?.first else {
                    resetTransientGesture()
                    return
                }

                let points = try observation.recognizedPoints(.all)
                guard let wrist = points[.wrist]?.location,
                      let thumb = points[.thumbTip]?.location,
                      let index = points[.indexTip]?.location else {
                    resetTransientGesture()
                    return
                }

                let palmScale = max(distance(wrist, points[.middleMCP]?.location ?? wrist), 0.05)
                let pinch = distance(thumb, index) / palmScale < 0.45

                let openFingers = [
                    points[.indexTip]?.location,
                    points[.middleTip]?.location,
                    points[.ringTip]?.location,
                    points[.littleTip]?.location
                ].compactMap { $0 }.filter {
                    distance(wrist, $0) / palmScale > 2.0
                }.count

                let openPalm = openFingers >= 3
                let handCenter = CGPoint(x: (thumb.x + index.x) / 2, y: (thumb.y + index.y) / 2)

                if mode == .receiver {
                    if openPalm {
                        gestureState = .released
                        releaseCooldown = 18
                    } else {
                        gestureState = .idle
                    }
                    return
                }

                if pinch {
                    pinchFrames += 1
                    if pinchFrames >= 5 {
                        pinchOrigin = pinchOrigin ?? handCenter
                        gestureState = .pinchLocked
                    }
                    return
                }

                if pinchOrigin != nil {
                    if let origin = pinchOrigin, distance(origin, handCenter) > 0.16 {
                        movementFrames += 1
                        gestureState = .moving
                    }

                    if openPalm && movementFrames >= 2 {
                        gestureState = .released
                        releaseCooldown = 18
                        pinchFrames = 0
                        movementFrames = 0
                        pinchOrigin = nil
                        return
                    }
                }

                resetTransientGesture()
            } catch {
                resetTransientGesture()
            }
        }

        private func resetTransientGesture() {
            pinchFrames = max(0, pinchFrames - 1)
            if pinchFrames == 0 {
                pinchOrigin = nil
                movementFrames = 0
                if gestureState != .released {
                    gestureState = .idle
                }
            }
        }

        private func distance(_ a: CGPoint, _ b: CGPoint) -> CGFloat {
            hypot(a.x - b.x, a.y - b.y)
        }
    }

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var videoPreviewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
}
