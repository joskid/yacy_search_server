// ResultURLs.java
// -----------------------
// part of YaCy
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://yacy.net
// Frankfurt, Germany, 2004
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
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

package de.anomic.crawler;

import java.net.MalformedURLException;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.yacy.cora.storage.ScoreCluster;
import net.yacy.cora.storage.DynamicScore;
import net.yacy.kelondro.data.meta.DigestURI;
import net.yacy.kelondro.data.meta.URIMetadataRow;
import net.yacy.kelondro.logging.Log;
import net.yacy.kelondro.order.Bitfield;
import net.yacy.kelondro.util.ReverseMapIterator;

import de.anomic.crawler.retrieval.EventOrigin;

public final class ResultURLs {

    private final Map<EventOrigin, Map<String, InitExecEntry>> resultStacks; // a mapping from urlHash to Entries
    private final Map<EventOrigin, DynamicScore<String>> resultDomains;

    public class InitExecEntry {
        public byte[] initiatorHash, executorHash;
        public InitExecEntry(final byte[] initiatorHash, final byte[] executorHash) {
            this.initiatorHash = initiatorHash;
            this.executorHash = executorHash;
        }
    }
    
    public ResultURLs(int initialStackCapacity) {
        // init result stacks
        resultStacks = new ConcurrentHashMap<EventOrigin, Map<String, InitExecEntry>>(initialStackCapacity);
        resultDomains = new ConcurrentHashMap<EventOrigin, DynamicScore<String>>(initialStackCapacity);
        for (EventOrigin origin: EventOrigin.values()) {
            resultStacks.put(origin, new LinkedHashMap<String, InitExecEntry>());
            resultDomains.put(origin, new ScoreCluster<String>());
        }
    }

    public synchronized void stack(
            final URIMetadataRow e,
            final byte[] initiatorHash,
            final byte[] executorHash,
            final EventOrigin stackType) {
        // assert initiatorHash != null; // null == proxy !
        assert executorHash != null;
        if (e == null) { return; }
        try {
            final Map<String, InitExecEntry> resultStack = getStack(stackType);
            if (resultStack != null) {
                resultStack.put(new String(e.hash()), new InitExecEntry(initiatorHash, executorHash));
            }
        } catch (final Exception ex) {
            System.out.println("INTERNAL ERROR in newEntry/2: " + ex.toString());
            return;
        }
        try {
            final DynamicScore<String> domains = getDomains(stackType);
            if (domains != null) {
                domains.inc(e.metadata().url().getHost());
            }
        } catch (final Exception ex) {
            System.out.println("INTERNAL ERROR in newEntry/3: " + ex.toString());
            return;
        }
    }
    
    public int getStackSize(final EventOrigin stack) {
        final Map<String, InitExecEntry> resultStack = getStack(stack);
        if (resultStack == null) return 0;
        return resultStack.size();
    }
    
    public int getDomainListSize(final EventOrigin stack) {
        final DynamicScore<String> domains = getDomains(stack);
        if (domains == null) return 0;
        return domains.size();
    }
    
    public Iterator<Map.Entry<String, InitExecEntry>> results(final EventOrigin stack) {
        final Map<String, InitExecEntry> resultStack = getStack(stack);
        if (resultStack == null) return new LinkedHashMap<String, InitExecEntry>().entrySet().iterator();
        return new ReverseMapIterator<String, InitExecEntry>(resultStack);
    }

    /**
     * iterate all domains in the result domain statistic
     * @return iterator of domains in reverse order (downwards)
     */
    public Iterator<String> domains(final EventOrigin stack) {
        assert getDomains(stack) != null : "getDomains(" + stack + ") = null";
        return getDomains(stack).keys(false);
    }
    
    public int deleteDomain(final EventOrigin stack, String host, String hosthash) {
        assert host != null : "host = null";
        assert hosthash.length() == 6;
        final Iterator<Map.Entry<String, InitExecEntry>> i = results(stack);
        Map.Entry<String, InitExecEntry> w;
        String urlhash;
        while (i.hasNext()) {
            w = i.next();
            urlhash = w.getKey();
            if (urlhash == null || urlhash.substring(6).equals(hosthash)) i.remove();
        }
        assert getDomains(stack) != null : "getDomains(" + stack + ") = null";
        return getDomains(stack).delete(host);
    }
    
    /**
     * return the count of the domain
     * @param stack type
     * @param domain name
     * @return the number of occurrences of the domain in the stack statistics
     */
    public int domainCount(final EventOrigin stack, String domain) {
        assert domain != null : "domain = null";
        assert getDomains(stack) != null : "getDomains(" + stack + ") = null";
        return getDomains(stack).get(domain);
    }
    
    /**
     * returns the stack identified by the id <em>stack</em>
     * 
     * @param stack id of resultStack
     * @return null if stack does not exist (id is unknown or stack is null (which should not occur and an error is logged))
     */
    private Map<String, InitExecEntry> getStack(final EventOrigin stack) {
        return resultStacks.get(stack);
    }
    private DynamicScore<String> getDomains(final EventOrigin stack) {
        return resultDomains.get(stack);
    }

    public void clearStacks() {
        for (EventOrigin origin: EventOrigin.values()) clearStack(origin);
    }
    
    public synchronized void clearStack(final EventOrigin stack) {
        final Map<String, InitExecEntry> resultStack = getStack(stack);
        if (resultStack != null) resultStack.clear();
        final DynamicScore<String> resultDomains = getDomains(stack);
        if (resultDomains != null) {
            // we do not clear this completely, just remove most of the less important entries
            resultDomains.shrinkToMaxSize(100);
            resultDomains.shrinkToMinScore(2);
        }
    }

    public synchronized boolean remove(final String urlHash) {
        if (urlHash == null) return false;
        Map<String, InitExecEntry> resultStack;
        for (EventOrigin origin: EventOrigin.values()) {
            resultStack = getStack(origin);
            if (resultStack != null) resultStack.remove(urlHash);
        }
        return true;
    }
    
    /**
     * test and benchmark
     * @param args
     */
    public static void main(final String[] args) {
        final ResultURLs results = new ResultURLs(10);
        try {
            final DigestURI url = new DigestURI("http", "www.yacy.net", 80, "/");
            final URIMetadataRow urlRef = new URIMetadataRow(url, "YaCy Homepage", "", "", "", new Date(), new Date(), new Date(), "", new byte[] {}, 123, 42, '?', new Bitfield(), "de", 0, 0, 0, 0, 0, 0);
            EventOrigin stackNo = EventOrigin.LOCAL_CRAWLING;
            System.out.println("valid test:\n=======");
            // add
            results.stack(urlRef, urlRef.hash(), url.hash(), stackNo);
            // size
            System.out.println("size of stack:\t"+ results.getStackSize(stackNo));
        } catch (final MalformedURLException e) {
            Log.logException(e);
        }
    }

}
