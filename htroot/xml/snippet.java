package xml;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;

import de.anomic.http.httpHeader;
import de.anomic.plasma.plasmaURL;
import de.anomic.kelondro.kelondroMSetTools;
import de.anomic.net.URL;
import de.anomic.plasma.plasmaCondenser;
import de.anomic.plasma.plasmaSearchQuery;
import de.anomic.plasma.plasmaSnippetCache;
import de.anomic.plasma.plasmaSwitchboard;
import de.anomic.server.serverObjects;
import de.anomic.server.serverSwitch;

public class snippet {
    public static serverObjects respond(httpHeader header, serverObjects post, serverSwitch env) throws MalformedURLException {
        // return variable that accumulates replacements
        plasmaSwitchboard switchboard = (plasmaSwitchboard) env;
        serverObjects prop = new serverObjects();
        
        //get the timeout for snippet-fetching
        int mediasnippet_timeout = 15000;
        int textsnippet_timeout = 10000;
        mediasnippet_timeout = Integer.parseInt(env.getConfig("timeout_text", "15000"));
        textsnippet_timeout = Integer.parseInt(env.getConfig("timeout_media", "10000"));
        	
        // getting url
        String urlString = post.get("url", "");
        URL url = new URL(urlString);
        prop.put("urlHash",plasmaURL.urlHash(url));
        
        // if 'remove' is set to true, then RWI references to URLs that do not have the snippet are removed
        boolean remove = post.get("remove", "false").equals("true");
        
        // boolean line_end_with_punctuation
        boolean pre = post.get("pre", "false").equals("true");
        
        // type of media
        String media = post.get("media", "text");
        
        String querystring = post.get("search", "").trim();
        if ((querystring.length() > 2) && (querystring.charAt(0) == '"') && (querystring.charAt(querystring.length() - 1) == '"')) {
            querystring = querystring.substring(1, querystring.length() - 1).trim();
        }        
        final TreeSet[] query = plasmaSearchQuery.cleanQuery(querystring);
        Set queryHashes = plasmaCondenser.words2hashes(query[0]);
        
        // filter out stopwords
        final TreeSet filtered = kelondroMSetTools.joinConstructive(query[0], plasmaSwitchboard.stopwords);
        if (filtered.size() > 0) {
            kelondroMSetTools.excludeDestructive(query[0], plasmaSwitchboard.stopwords);
        }
        
        // find snippet
        if (media.equals("text")) {
            // attach text snippet
            plasmaSnippetCache.TextSnippet snippet = plasmaSnippetCache.retrieveTextSnippet(url, queryHashes, true, pre, 260, textsnippet_timeout);
            prop.put("status",snippet.getErrorCode());
            if (snippet.getErrorCode() < 11) {
                // no problems occurred
                //prop.put("text", (snippet.exists()) ? snippet.getLineMarked(queryHashes) : "unknown");
                prop.putASIS("text", (snippet.exists()) ? snippet.getLineMarked(queryHashes) : "unknown"); //FIXME: the ASIS should not be needed, but we have still htmlcode in .java files
            } else {
                // problems with snippet fetch
               prop.put("text", (remove) ? plasmaSnippetCache.failConsequences(snippet, queryHashes) : snippet.getError());
            }
            prop.put("link", 0);
            prop.put("links", 0);
            prop.put("favicon",snippet.getFavicon()==null?"":snippet.getFavicon().toString());
        } else {
            // attach media information
            ArrayList mediaSnippets = plasmaSnippetCache.retrieveMediaSnippets(url, queryHashes, media, true, mediasnippet_timeout);
            plasmaSnippetCache.MediaSnippet ms;
            for (int i = 0; i < mediaSnippets.size(); i++) {
                ms = (plasmaSnippetCache.MediaSnippet) mediaSnippets.get(i);
                try {
                    url = new URL(ms.href);
                } catch (MalformedURLException e) {
                    continue;
                }
                prop.put("link_" + i + "_type", ms.type);
                prop.put("link_" + i + "_href", ms.href);
                prop.put("link_" + i + "_code", switchboard.licensedURLs.aquireLicense(url));
                prop.put("link_" + i + "_name", ms.name);
                prop.put("link_" + i + "_attr", ms.attr);
            }
            //System.out.println("DEBUG: " + mediaSnippets.size() + " ENTRIES IN MEDIA SNIPPET LINKS for url " + urlString);
            prop.put("text", "");
            prop.put("link", mediaSnippets.size());
            prop.put("links", mediaSnippets.size());
            prop.put("favicon","");
        }
        
        
        // return rewrite properties
        return prop;
    }
}
