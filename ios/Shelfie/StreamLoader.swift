import AVFoundation
import Foundation
import UniformTypeIdentifiers

/**
 Routes AVPlayer streaming through a URLSession that trusts self-signed
 certificates. AVFoundation performs its own TLS validation with no public
 override, so HTTPS stream URLs are rewritten to a custom scheme and the
 resource loader fetches the bytes (honoring range requests) itself.
 */
final class StreamLoader: NSObject, AVAssetResourceLoaderDelegate, URLSessionDataDelegate {

    private static let scheme = "shelfia-https"

    /** Wraps an https URL into the custom loader scheme; nil for anything else. */
    static func wrap(_ url: URL) -> URL? {
        guard url.scheme == "https",
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        else { return nil }
        components.scheme = scheme
        return components.url
    }

    private lazy var session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
    private var loading: [Int: AVAssetResourceLoadingRequest] = [:]
    private let lock = NSLock()

    // MARK: AVAssetResourceLoaderDelegate

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        guard let url = loadingRequest.request.url,
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme == Self.scheme
        else { return false }
        components.scheme = "https"
        guard let real = components.url else { return false }

        var request = URLRequest(url: real)
        if let dataRequest = loadingRequest.dataRequest {
            let offset = dataRequest.requestedOffset
            if dataRequest.requestsAllDataToEndOfResource {
                request.setValue("bytes=\(offset)-", forHTTPHeaderField: "Range")
            } else {
                let end = offset + Int64(dataRequest.requestedLength) - 1
                request.setValue("bytes=\(offset)-\(end)", forHTTPHeaderField: "Range")
            }
        }
        let task = session.dataTask(with: request)
        lock.lock()
        loading[task.taskIdentifier] = loadingRequest
        lock.unlock()
        task.resume()
        return true
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        didCancel loadingRequest: AVAssetResourceLoadingRequest
    ) {
        lock.lock()
        let identifier = loading.first { $0.value === loadingRequest }?.key
        if let identifier { loading[identifier] = nil }
        lock.unlock()
        guard let identifier else { return }
        session.getAllTasks { tasks in
            tasks.first { $0.taskIdentifier == identifier }?.cancel()
        }
    }

    // MARK: URLSessionDataDelegate

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
           let trust = challenge.protectionSpace.serverTrust {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.performDefaultHandling, nil)
        }
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        lock.lock()
        let request = loading[dataTask.taskIdentifier]
        lock.unlock()
        if let info = request?.contentInformationRequest, let http = response as? HTTPURLResponse {
            if let mime = http.mimeType, let type = UTType(mimeType: mime) {
                info.contentType = type.identifier
            }
            info.isByteRangeAccessSupported = true
            // Total size from "Content-Range: bytes 0-1/12345", else the body length.
            if let range = http.value(forHTTPHeaderField: "Content-Range"),
               let totalPart = range.split(separator: "/").last,
               let total = Int64(totalPart) {
                info.contentLength = total
            } else if http.expectedContentLength >= 0 {
                info.contentLength = http.expectedContentLength
            }
        }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        let request = loading[dataTask.taskIdentifier]
        lock.unlock()
        guard let request, !request.isCancelled else { return }
        request.dataRequest?.respond(with: data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        let request = loading.removeValue(forKey: task.taskIdentifier)
        lock.unlock()
        guard let request, !request.isCancelled else { return }
        if let error {
            request.finishLoading(with: error)
        } else {
            request.finishLoading()
        }
    }
}
