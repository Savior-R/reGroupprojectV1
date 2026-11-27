package com.shijiangtai.m6training.domain.port;

import java.util.Optional;

/**
 * Reads raw material bytes so a copied template can own an independent object.
 *
 * <p>{@code m3_classroom.application.port.MaterialObjectStorage} is write/delete-only and is
 * owned by M3, so M6 adds this read-only seam instead of changing the M3 contract.
 */
public interface TrainingMaterialObjectReader {

    Optional<byte[]> read(String objectKey);
}
