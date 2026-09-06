package com.rspsi.misc;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class ArrayUtils {

    /**
     * Utility function for merging integer arrays.
     *
     * @param arrays
     * @return
     */
    public final static int[] merge(final int[] ...arrays ) {
        int size = 0;
        for ( int[] a: arrays )
            size += a.length;

        int[] res = new int[size];

        int destPos = 0;
        for ( int i = 0; i < arrays.length; i++ ) {
            if ( i > 0 ) destPos += arrays[i-1].length;
            int length = arrays[i].length;
            System.arraycopy(arrays[i], 0, res, destPos, length);
        }

        return res;
    }

    /**
     * Utility function for converting integer arrays to byte arrays.
     *
     * @param values
     * @return
     */
    public final static byte[] integersToBytes(int[] values)
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DataOutputStream dos = new DataOutputStream(baos);
            for(int i=0; i < values.length; ++i)
            {
                    dos.writeInt(values[i]);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return baos.toByteArray();
    }
}
