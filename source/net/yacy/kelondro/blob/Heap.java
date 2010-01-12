// kelondroBLOBHeap.java
// (C) 2008 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 09.07.2008 on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate: 2008-03-14 01:16:04 +0100 (Fr, 14 Mrz 2008) $
// $LastChangedRevision$
// $LastChangedBy$
//
// LICENSE
// 
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package net.yacy.kelondro.blob;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.yacy.kelondro.index.RowSpaceExceededException;
import net.yacy.kelondro.io.AbstractWriter;
import net.yacy.kelondro.logging.Log;
import net.yacy.kelondro.order.ByteOrder;
import net.yacy.kelondro.order.CloneableIterator;
import net.yacy.kelondro.order.NaturalOrder;


public final class Heap extends HeapModifier implements BLOB {

    private HashMap<String, byte[]> buffer;     // a write buffer to limit IO to the file; attention: Maps cannot use byte[] as key
    private int                     buffersize; // bytes that are buffered in buffer
    private final int               buffermax;  // maximum size of the buffer
    
    /*
     * This class implements a BLOB management based on a sequence of records in a random access file
     * The data structure is:
     * file   :== record*
     * record :== reclen key blob
     * reclen :== <4 byte integer == length of key and blob>
     * key    :== <bytes as defined with keylen, if first byte is zero then record is empty>
     * blob   :== <bytes of length reclen - keylen>
     * that means that each record has the size reclen+4
     * 
     * The elements are organized in two data structures:
     * index<kelondroBytesLongMap> : key/seek relation for used records
     * free<ArrayList<Integer[]>>  : list of {size, seek} pairs denoting space and position of free records
     * 
     * Because the blob sizes are stored with integers, one entry may not exceed 2GB
     * 
     * If a record is removed, it becomes a free record.
     * New records are either appended to the end of the file or filled into a free record.
     * A free record must either fit exactly to the size of the new record, or an old record is splitted
     * into a filled and a new, smaller empty record.
     */

    /**
     * create a heap file: a arbitrary number of BLOBs, indexed by an access key
     * The heap file will be indexed upon initialization.
     * @param heapFile
     * @param keylength
     * @param ordering
     * @throws IOException
     */
    public Heap(
            final File heapFile,
            final int keylength,
            final ByteOrder ordering,
            int buffermax) throws IOException {
        super(heapFile, keylength, ordering);
        this.buffermax = buffermax;
        this.buffer = new HashMap<String, byte[]>();
        this.buffersize = 0;
        /*
        // DEBUG
        Iterator<byte[]> i = index.keys(true, null);
        //byte[] b;
        int c = 0;
        while (i.hasNext()) {
            key = i.next();
            System.out.println("*** DEBUG BLOBHeap " + this.name() + " KEY=" + new String(key));
            //b = get(key);
            //System.out.println("BLOB=" + new String(b));
            //System.out.println();
            c++;
            if (c >= 20) break;
        }
        System.out.println("*** DEBUG - counted " + c + " BLOBs");
        */
    }
    
    /**
     * the number of BLOBs in the heap
     * @return the number of BLOBs in the heap
     */
    @Override
    public synchronized int size() {
        return super.size() + ((this.buffer == null) ? 0 : this.buffer.size());
    }

    
    /**
     * test if a key is in the heap file. This does not need any IO, because it uses only the ram index
     * @param key
     * @return true if the key exists, false otherwise
     */
    @Override
    public synchronized boolean has(final byte[] key) {
        assert index != null;
        assert this.keylength == key.length : this.keylength + "!=" + key.length;
        
        // check the buffer
        if (this.buffer.containsKey(new String(key))) return true;
        return super.has(key);
    }

    /**
     * add a BLOB to the heap: this adds the blob always to the end of the file
     * @param key
     * @param blob
     * @throws IOException
     * @throws RowSpaceExceededException 
     */
    private void add(final byte[] key, final byte[] blob) throws IOException, RowSpaceExceededException {
        assert blob.length > 0;
        assert key.length == this.keylength;
        assert this.keylength == key.length : this.keylength + "!=" + key.length;
        if ((blob == null) || (blob.length == 0)) return;
        final int pos = (int) file.length();
        index.put(key, pos);
        file.seek(pos);
        file.writeInt(key.length + blob.length);
        file.write(key);
        file.write(blob, 0, blob.length);
    }
    
    /**
     * flush the buffer completely
     * this is like adding all elements of the buffer, but it needs only one IO access
     * @throws IOException
     * @throws RowSpaceExceededException 
     */
    private void flushBuffer() throws IOException, RowSpaceExceededException {
        // check size of buffer
        Iterator<Map.Entry<String, byte[]>> i = this.buffer.entrySet().iterator();
        int l = 0;
        while (i.hasNext()) l += i.next().getValue().length;
        assert l == this.buffersize;
        
        // simulate write: this whole code block is only here to test the assert at the end of the block; remove after testing
        i = this.buffer.entrySet().iterator();
        int posBuffer = 0;
        Map.Entry<String, byte[]> entry;
        byte[] key, blob;
        while (i.hasNext()) {
            entry = i.next();
            key = entry.getKey().getBytes();
            assert key.length == this.keylength : "key.length = " + key.length + ", this.keylength = " + this.keylength;
            blob = entry.getValue();
            posBuffer += 4 + key.length + blob.length;
        }
        assert l + (4 + this.keylength) * this.buffer.size() == posBuffer : "l = " + l + ", this.keylength = " + this.keylength + ", this.buffer.size() = " + this.buffer.size() + ", posBuffer = " + posBuffer;
        
        // append all contents of the buffer into one byte[]
        i = this.buffer.entrySet().iterator();
        final long pos = file.length();
        long posFile = pos;
        posBuffer = 0;
        byte[] ba = new byte[l + (4 + this.keylength) * this.buffer.size()];
        byte[] b;
        while (i.hasNext()) {
            entry = i.next();
            key = entry.getKey().getBytes();
            assert key.length == this.keylength : "key.length = " + key.length + ", this.keylength = " + this.keylength;
            blob = entry.getValue();
            index.put(key, posFile);
            b = AbstractWriter.int2array(key.length + blob.length);
            assert b.length == 4;
            assert posBuffer + 4 < ba.length : "posBuffer = " + posBuffer + ", ba.length = " + ba.length;
            System.arraycopy(b, 0, ba, posBuffer, 4);
            assert posBuffer + 4 + key.length <= ba.length : "posBuffer = " + posBuffer + ", key.length = " + key.length + ", ba.length = " + ba.length;
            System.arraycopy(key, 0, ba, posBuffer + 4, key.length);
            assert posBuffer + 4 + key.length + blob.length <= ba.length : "posBuffer = " + posBuffer + ", key.length = " + key.length + ", blob.length = " + blob.length + ", ba.length = " + ba.length;
            //System.out.println("*** DEBUG posFile=" + posFile + ",blob.length=" + blob.length + ",ba.length=" + ba.length + ",posBuffer=" + posBuffer + ",key.length=" + key.length);
            //System.err.println("*** DEBUG posFile=" + posFile + ",blob.length=" + blob.length + ",ba.length=" + ba.length + ",posBuffer=" + posBuffer + ",key.length=" + key.length);
            System.arraycopy(blob, 0, ba, posBuffer + 4 + this.keylength, blob.length); //java.lang.ArrayIndexOutOfBoundsException here
            posFile += 4 + key.length + blob.length;
            posBuffer += 4 + key.length + blob.length;
        }
        assert ba.length == posBuffer; // must fit exactly
        this.file.seek(pos);
        this.file.write(ba);
        this.buffer.clear();
        this.buffersize = 0;
    }
    
    /**
     * read a blob from the heap
     * @param key
     * @return
     * @throws IOException
     */
    @Override
    public synchronized byte[] get(final byte[] key) throws IOException {
        assert this.keylength == key.length : this.keylength + "!=" + key.length;
        
        // check the buffer
        byte[] blob = this.buffer.get(new String(key));
        if (blob != null) return blob;
        
        return super.get(key);
    }

    /**
     * retrieve the size of the BLOB
     * @param key
     * @return the size of the BLOB or -1 if the BLOB does not exist
     * @throws IOException
     */
    @Override
    public synchronized long length(final byte[] key) throws IOException {
        assert this.keylength == key.length : this.keylength + "!=" + key.length;
        
        // check the buffer
        byte[] blob = this.buffer.get(new String(key));
        if (blob != null) return blob.length;

        return super.length(key);
    }
    
    /**
     * clears the content of the database
     * @throws IOException
     */
    @Override
    public synchronized void clear() throws IOException {
    	this.buffer.clear();
        this.buffersize = 0;
        super.clear();
    }

    /**
     * close the BLOB table
     */
    @Override
    public synchronized void close(final boolean writeIDX) {
    	if (file != null && buffer != null) {
            try {
                flushBuffer();
            } catch (IOException e) {
                Log.logException(e);
            } catch (RowSpaceExceededException e) {
                Log.logException(e);
            }
        }
    	this.buffer = null;
    	super.close(writeIDX);
    	assert file == null;
    }
    
    @Override
    public synchronized void close() {
        this.close(true);
    }
    
    @Override
    public void finalize() {
        this.close();
    }
    
    public int getBuffermax() {
        return this.buffermax;
    }

    /**
     * write a whole byte array as BLOB to the table
     * @param key  the primary key
     * @param b
     * @throws IOException
     * @throws RowSpaceExceededException 
     */
    @Override
    public synchronized void put(final byte[] key, final byte[] b) throws IOException, RowSpaceExceededException {
        assert this.keylength == key.length : this.keylength + "!=" + key.length;
        
        // we do not write records of length 0 into the BLOB
        if (b.length == 0) return;
        
        // first remove the old entry (removes from buffer and file)
        // TODO: this can be enhanced!
        this.remove(key);
        
        // then look if we can use a free entry
        if (putToGap(key, b)) return; 
        
        // if there is not enough space in the buffer, flush all
        if (this.buffersize + b.length > buffermax) {
            // this is too big. Flush everything
            super.shrinkWithGapsAtEnd();
            flushBuffer();
            if (b.length > buffermax) {
                this.add(key, b);
            } else {
                this.buffer.put(new String(key), b);
                this.buffersize += b.length;
            }
            return;
        }
        
        // add entry to buffer
        this.buffer.put(new String(key), b);
        this.buffersize += b.length;
    }
    
    private boolean putToGap(final byte[] key, final byte[] b) throws IOException, RowSpaceExceededException {
        assert this.keylength == key.length : this.keylength + "!=" + key.length;
        
        // we do not write records of length 0 into the BLOB
        if (b.length == 0) return true;
        
        // then look if we can use a free entry
        if (this.free.isEmpty()) return false;
        
        // find the largest entry
        long lseek = -1;
        int  lsize = 0;
        final int reclen = b.length + this.keylength;
        Map.Entry<Long, Integer> entry;
        Iterator<Map.Entry<Long, Integer>> i = this.free.entrySet().iterator();
        while (i.hasNext()) {
            entry = i.next();
            if (entry.getValue().intValue() == reclen) {
                // we found an entry that has exactly the size that we need!
                // we use that entry and stop looking for a larger entry
                
                // add the entry to the index
                this.index.put(key, entry.getKey());
                
                // write to file
                file.seek(entry.getKey().longValue());
                final int reclenf = file.readInt();
                assert reclenf == reclen;
                file.write(key);
                file.write(b);

                // remove the entry from the free list
                i.remove();
                
                 //System.out.println("*** DEBUG BLOB: replaced-fit record at " + entry.seek + ", reclen=" + reclen + ", key=" + new String(key));
                
                // finished!
                return true;
            }
            // look for the biggest size
            if (entry.getValue() > lsize) {
                lseek = entry.getKey();
                lsize = entry.getValue();
            }
        }
        
        // check if the found entry is large enough
        if (lsize > reclen + 4) {
            // split the free entry into two new entries
            // if would be sufficient if lsize = reclen + 4, but this would mean to create
            // an empty entry with zero next bytes for BLOB and key, which is not very good for the
            // data structure in the file
            
            // write the new entry
            file.seek(lseek);
            file.writeInt(reclen);
            file.write(key);
            file.write(b);
            
            // add the index to the new entry
            index.put(key, lseek);
            
            // define the new empty entry
            final int newfreereclen = lsize - reclen - 4;
            assert newfreereclen > 0;
            file.writeInt(newfreereclen);
            
            // remove the old free entry
            this.free.remove(lseek);
            
            // add a new free entry
            this.free.put(lseek + 4 + reclen, newfreereclen);
            
            //System.out.println("*** DEBUG BLOB: replaced-split record at " + lseek + ", reclen=" + reclen + ", new reclen=" + newfreereclen + ", key=" + new String(key));
            
            // finished!
            return true;
        }
        // could not insert to gap
        return false;
    }

    /**
     * remove a BLOB
     * @param key  the primary key
     * @throws IOException
     */
    @Override
    public synchronized void remove(final byte[] key) throws IOException {
        assert this.keylength == key.length : this.keylength + "!=" + key.length;
        
        // check the buffer
        byte[] blob = this.buffer.remove(new String(key));
        if (blob != null) {
            this.buffersize -= blob.length;
            return;
        }
        
        super.remove(key);
    }
    
    /**
     * iterator over all keys
     * @param up
     * @param rotating
     * @return
     * @throws IOException
     */
    @Override
    public synchronized CloneableIterator<byte[]> keys(final boolean up, final boolean rotating) throws IOException {
        try {
            this.flushBuffer();
        } catch (RowSpaceExceededException e) {
            Log.logException(e);
        }
        return super.keys(up, rotating);
    }

    /**
     * iterate over all keys
     * @param up
     * @param firstKey
     * @return
     * @throws IOException
     */
    @Override
    public synchronized CloneableIterator<byte[]> keys(final boolean up, final byte[] firstKey) throws IOException {
        try {
            this.flushBuffer();
        } catch (RowSpaceExceededException e) {
            Log.logException(e);
        }
        return super.keys(up, firstKey);
    }

    @Override
    public synchronized long length() {
        return super.length() + this.buffersize;
    }

    public static void heaptest() {
        final File f = new File("/Users/admin/blobtest.heap");
        try {
            //f.delete();
            final Heap heap = new Heap(f, 12, NaturalOrder.naturalOrder, 1024 * 512);
            heap.put("aaaaaaaaaaaa".getBytes(), "eins zwei drei".getBytes());
            heap.put("aaaaaaaaaaab".getBytes(), "vier fuenf sechs".getBytes());
            heap.put("aaaaaaaaaaac".getBytes(), "sieben acht neun".getBytes());
            heap.put("aaaaaaaaaaad".getBytes(), "zehn elf zwoelf".getBytes());
            // iterate over keys
            Iterator<byte[]> i = heap.index.keys(true, null);
            while (i.hasNext()) {
                System.out.println("key_a: " + new String(i.next()));
            }
            i = heap.keys(true, false);
            while (i.hasNext()) {
                System.out.println("key_b: " + new String(i.next()));
            }
            heap.remove("aaaaaaaaaaab".getBytes());
            heap.remove("aaaaaaaaaaac".getBytes());
            heap.put("aaaaaaaaaaaX".getBytes(), "WXYZ".getBytes());
            heap.close(true);
        } catch (final IOException e) {
            Log.logException(e);
        } catch (RowSpaceExceededException e) {
            Log.logException(e);
        }
    }

    private static Map<String, String> map(final String a, final String b) {
        HashMap<String, String> m = new HashMap<String, String>();
        m.put(a, b);
        return m;
    }
    
    public static void maptest() {
        final File f = new File("/Users/admin/blobtest.heap");
        try {
            //f.delete();
            final MapView heap = new MapView(new Heap(f, 12, NaturalOrder.naturalOrder, 1024 * 512), 500, '_');
            heap.put("aaaaaaaaaaaa", map("aaaaaaaaaaaa", "eins zwei drei"));
            heap.put("aaaaaaaaaaab", map("aaaaaaaaaaab", "vier fuenf sechs"));
            heap.put("aaaaaaaaaaac", map("aaaaaaaaaaac", "sieben acht neun"));
            heap.put("aaaaaaaaaaad", map("aaaaaaaaaaad", "zehn elf zwoelf"));
            heap.remove("aaaaaaaaaaab");
            heap.remove("aaaaaaaaaaac");
            heap.put("aaaaaaaaaaaX", map("aaaaaaaaaaad", "WXYZ"));
            heap.close();
        } catch (final IOException e) {
            Log.logException(e);
        } catch (RowSpaceExceededException e) {
            Log.logException(e);
        }
    }
    
    public static void main(final String[] args) {
        //heaptest();
        maptest();
    }

}
