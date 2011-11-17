package org.jboss.shrinkwrap.impl.base.exporter;

import java.util.Random;

import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.ByteArrayAsset;

/**
 * An {@link Asset} which contains a megabyte of dummy data
 *
 * @author <a href="mailto:andrew.rubinger@jboss.org">ALR</a>
 */
class MegaByteAsset extends ByteArrayAsset implements Asset {
    /**
     * Dummy megabyte
     */
    private static int MEGA = 1024 * 1024;

    private static final Random random = new Random();

    private MegaByteAsset(final byte[] content) {
        super(content);
    }

    static MegaByteAsset newInstance() {
        /**
         * Bytes must be random/distributed so that compressing these in ZIP isn't too efficient
         */
        final byte[] content = new byte[MEGA];
        random.nextBytes(content);
        return new MegaByteAsset(content);
    }
}