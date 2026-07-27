/**
 * Object upload for the analytical tier: one PUT for a small part, multipart for a large one.
 *
 * The thresholds come from R-013 and are the same numbers the telemetry path is specified
 * with: switch to multipart above 100 MB, in 8 MB parts. Both are overridable per call for
 * one reason only — a test cannot allocate 100 MB inside a 128 MB isolate, so it exercises
 * the same code with R2's own minimum part size instead of a stub.
 */
export const MULTIPART_THRESHOLD_BYTES = 100 * 1024 * 1024
export const PART_SIZE_BYTES = 8 * 1024 * 1024

/**
 * R2 refuses a multipart upload whose non-final parts are below 5 MiB, and miniflare enforces
 * it too — `completeMultipartUpload: Your proposed upload is smaller than the minimum allowed
 * object size`. A test that wants the multipart path must respect this.
 */
export const MIN_PART_SIZE_BYTES = 5 * 1024 * 1024

export interface UploadOptions {
  multipartThresholdBytes?: number
  partSizeBytes?: number
  httpMetadata?: R2PutOptions['httpMetadata']
}

export interface UploadResult {
  key: string
  bytes: number
  /** How many R2 parts were used. 1 means an ordinary PUT. */
  parts: number
  multipart: boolean
}

/**
 * Writes `bytes` to `key`, replacing whatever was there.
 *
 * A single PUT is atomic — a reader sees the whole old object or the whole new one, never a
 * mixture — which is what makes replacing a month's only part safe with no coordination at
 * all. A multipart upload is atomic in the same way at the moment it completes; the parts are
 * invisible until then.
 */
export async function putArchiveObject(
  bucket: R2Bucket,
  key: string,
  bytes: Uint8Array,
  options: UploadOptions = {},
): Promise<UploadResult> {
  const threshold = options.multipartThresholdBytes ?? MULTIPART_THRESHOLD_BYTES
  const partSize = options.partSizeBytes ?? PART_SIZE_BYTES
  const httpMetadata = options.httpMetadata ?? { contentType: 'application/vnd.apache.parquet' }

  // Below either bound a single PUT is both simpler and cheaper. The second test matters as
  // much as the first: a "multipart" upload of one undersized part is rejected by R2, and
  // that is exactly the shape a low threshold would otherwise produce.
  if (bytes.byteLength <= threshold || bytes.byteLength <= partSize) {
    const object = await bucket.put(key, bytes, { httpMetadata })
    return { key, bytes: object?.size ?? bytes.byteLength, parts: 1, multipart: false }
  }

  const upload = await bucket.createMultipartUpload(key, { httpMetadata })
  try {
    const parts: R2UploadedPart[] = []
    for (let offset = 0, number = 1; offset < bytes.byteLength; offset += partSize, number++) {
      const slice = bytes.subarray(offset, Math.min(offset + partSize, bytes.byteLength))
      // Sequential, not parallel: the whole part list is already resident in this isolate's
      // 128 MB, and fanning out the writes would only add peak memory to that.
      parts.push(await upload.uploadPart(number, slice))
    }
    const object = await upload.complete(parts)
    return { key, bytes: object.size, parts: parts.length, multipart: true }
  } catch (error) {
    // An abandoned multipart upload keeps its parts billable until it is aborted, and nothing
    // else will ever clean it up: this Worker is the only writer of the prefix.
    await upload.abort().catch(() => undefined)
    throw error
  }
}
