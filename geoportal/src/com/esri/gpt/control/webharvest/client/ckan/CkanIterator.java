/*
 * Copyright 2015 Esri.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.esri.gpt.control.webharvest.client.ckan;

import com.esri.gpt.framework.ckan.CkanPackage;
import com.esri.gpt.framework.ckan.CkanPackageList;
import com.esri.gpt.framework.ckan.CkanParser;
import com.esri.gpt.framework.http.HttpClientRequest;
import com.esri.gpt.framework.http.StringHandler;
import com.esri.gpt.framework.http.crawl.HttpCrawlRequest;
import com.esri.gpt.framework.robots.Bots;
import com.esri.gpt.framework.robots.BotsMode;
import com.esri.gpt.framework.robots.BotsUtils;
import com.esri.gpt.framework.util.Val;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.apache.commons.httpclient.HttpMethodBase;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * CKAN iterator.
 */
public class CkanIterator implements Iterable<CkanPackage> {

  private final String baseUrl;
  private final BotsMode mode;
  private final String defaultUserAgent;

  public CkanIterator(URL baseUrl, BotsMode mode, String defaultUserAgent) {
    this.baseUrl = baseUrl.toExternalForm();
    this.mode = mode;
    this.defaultUserAgent = defaultUserAgent;
  }

  @Override
  public Iterator<CkanPackage> iterator() {
    Bots bots = BotsUtils.readBots(mode, baseUrl);
    try {
      JSONObject response = readJsonData(bots, baseUrl+"/package_list");
      if (response.has("result") && response.optBoolean("success", false)) {
        if (response.get("result") instanceof JSONArray) {
          CkanPackageList ckanPackageList = CkanParser.makePackageList(response.getJSONArray("result"));
          return new PackageListIterator(bots, ckanPackageList);
        } else if (response.get("result") instanceof JSONObject) {
          JSONObject result = response.getJSONObject("result");
          if (result.has("results") && result.get("results") instanceof JSONArray) {
            long count = result.getLong("count");
            JSONArray pkgs = result.getJSONArray("results");
            List<CkanPackage> pkgsArr = readPackages(pkgs);
            return new PackageSearchIterator(bots, pkgsArr, count);
          }
        }
      }
      return Collections.EMPTY_LIST.iterator();
    } catch (Exception ex) {
      return Collections.EMPTY_LIST.iterator();
    }
  }

  protected List<CkanPackage> readPackages(JSONArray pkgs) throws JSONException {
    ArrayList<CkanPackage> pkgsArr = new ArrayList<CkanPackage>();
    for (int i = 0; i < pkgs.length(); i++) {
      JSONObject pkgObject = pkgs.getJSONObject(i);
      CkanPackage pkg = CkanParser.makePackage(pkgObject);
      pkgsArr.add(pkg);
    }
    return pkgsArr;
  }

  protected HttpClientRequest newClientRequest(final Bots bots) {
    if (bots != null) {
      return new HttpCrawlRequest(bots) {
        @Override
        protected HttpMethodBase createMethod() throws IOException {
          HttpMethodBase method = super.createMethod();
          String userAgent = Val.chkStr(!Val.chkStr(bots.getUserAgent()).isEmpty()
                  ? bots.getUserAgent() : defaultUserAgent);
          if (!userAgent.isEmpty()) {
            method.addRequestHeader("User-Agent", userAgent);
          }
          return method;
        }
      };
    } else {
      return new HttpClientRequest() {
        @Override
        protected HttpMethodBase createMethod() throws IOException {
          HttpMethodBase method = super.createMethod();
          String userAgent = Val.chkStr(defaultUserAgent);
          if (!userAgent.isEmpty()) {
            method.addRequestHeader("User-Agent", userAgent);
          }
          return method;
        }
      };
    }
  }

  protected JSONObject readJsonData(Bots bots, String url) throws IOException, JSONException {
    HttpClientRequest request = newClientRequest(bots);
    request.setUrl(url);
    StringHandler handler = new StringHandler();
    request.setContentHandler(handler);
    request.execute();
    return new JSONObject(handler.getContent());
  }

  private class PackageSearchIterator implements Iterator<CkanPackage> {

    private final Bots bots;
    private Iterator<CkanPackage> pkgIter;
    private final long count;
    private long start;
    private CkanPackage pkg;
    private boolean done;

    public PackageSearchIterator(Bots bots, List<CkanPackage> firstPage, long count) {
      this.bots = bots;
      this.pkgIter = firstPage.iterator();
      this.count = count;
    }

    private void markDone() {
      pkgIter = null;
      pkg = null;
      done = true;
    }
    
    @Override
    public boolean hasNext() {
      if (done) return false;
      if (pkg!=null) return true;
      if (pkgIter==null) {
        markDone();
        return false;
      }
      if (pkgIter.hasNext()) {
        pkg = pkgIter.next();
        start++;
        return true;
      }
      
      if (start>=count) return false;
      
      pkgIter = null;
      try {
        JSONObject response = readJsonData(bots, baseUrl+"/package_search?start=" + start);
        if (response.has("result") && response.optBoolean("success", false) && response.get("result") instanceof JSONObject) {
          JSONObject result = response.getJSONObject("result");
          if (result.has("results") && result.get("results") instanceof JSONArray) {
            JSONArray pkgs = result.getJSONArray("results");
            List<CkanPackage> pkgsArr = readPackages(pkgs);
            if (!pkgsArr.isEmpty()) {
              pkgIter = pkgsArr.iterator();
              return hasNext();
            }
          }
        }
        markDone();
        return false;
      } catch (Exception ex) {
        markDone();
        return false;
      }
    }

    @Override
    public CkanPackage next() {
      if (pkg == null) {
        throw new IllegalStateException("No next package.");
      }
      CkanPackage nextPackage = pkg;
      pkg = null;
      return nextPackage;
    }

  }

  private class PackageListIterator implements Iterator<CkanPackage> {

    private final Bots bots;
    private final Iterator<String> idsIterator;
    private CkanPackage pkg;
    private boolean done;

    public PackageListIterator(Bots bots, CkanPackageList packageList) {
      this.bots = bots;
      this.idsIterator = packageList.getPackagesIds().iterator();
    }

    private void markDone() {
      pkg = null;
      done = true;
    }
    
    @Override
    public boolean hasNext() {
      if (done) {
        return false;
      }
      if (pkg != null) {
        return true;
      }
      if (idsIterator == null) {
        markDone();
        return false;
      }
      if (!idsIterator.hasNext()) {
        markDone();
        return false;
      }

      String packageId = idsIterator.next();
      try {
        JSONObject pkgObject = readJsonData(bots, baseUrl+"/package_show?id=" + packageId);
        if (pkgObject.has("result") && pkgObject.optBoolean("success", false) && pkgObject.get("result") instanceof JSONObject) {
          pkg = CkanParser.makePackage(pkgObject.getJSONObject("result"));
          return true;
        } else {
          return hasNext();
        }
      } catch (Exception ex) {
        markDone();
        return false;
      }
    }

    @Override
    public CkanPackage next() {
      if (pkg == null) {
        throw new IllegalStateException("No next package.");
      }
      CkanPackage nextPackage = pkg;
      pkg = null;
      return nextPackage;
    }

  }
}
