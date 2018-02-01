/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.trellisldp.app;

import static com.google.common.cache.CacheBuilder.newBuilder;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.HOURS;
import static org.apache.jena.query.DatasetFactory.createTxnMem;
import static org.apache.jena.query.DatasetFactory.wrap;
import static org.apache.jena.rdfconnection.RDFConnectionFactory.connect;
import static org.apache.jena.tdb2.DatabaseMgr.connectDatasetGraph;
import static org.trellisldp.app.TrellisUtils.getAuthFilters;
import static org.trellisldp.app.TrellisUtils.getCorsConfiguration;
import static org.trellisldp.app.TrellisUtils.getWebacConfiguration;

import io.dropwizard.Application;
import io.dropwizard.auth.chained.ChainedAuthFilter;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

import java.io.IOException;
import java.util.Optional;

import org.apache.jena.rdfconnection.RDFConnection;
import org.trellisldp.agent.SimpleAgent;
import org.trellisldp.api.BinaryService;
import org.trellisldp.api.CacheService;
import org.trellisldp.api.IOService;
import org.trellisldp.api.IdentifierService;
import org.trellisldp.api.MementoService;
import org.trellisldp.api.NamespaceService;
import org.trellisldp.api.ResourceService;
import org.trellisldp.app.config.RdfConnectionConfiguration;
import org.trellisldp.app.config.TrellisConfiguration;
import org.trellisldp.app.health.RDFConnectionHealthCheck;
import org.trellisldp.file.FileBinaryService;
import org.trellisldp.file.FileMementoService;
import org.trellisldp.http.AgentAuthorizationFilter;
import org.trellisldp.http.CacheControlFilter;
import org.trellisldp.http.CrossOriginResourceSharingFilter;
import org.trellisldp.http.LdpResource;
import org.trellisldp.http.WebAcFilter;
import org.trellisldp.id.UUIDGenerator;
import org.trellisldp.io.JenaIOService;
import org.trellisldp.namespaces.NamespacesJsonContext;
import org.trellisldp.triplestore.TriplestoreResourceService;
import org.trellisldp.webac.WebACService;

/**
 * @author acoburn
 */
public class TrellisApplication extends Application<TrellisConfiguration> {

    /**
     * The main entry point.
     * @param args the argument list
     * @throws Exception if something goes horribly awry
     */
    public static void main(final String[] args) throws Exception {
        new TrellisApplication().run(args);
    }

    @Override
    public String getName() {
        return "Trellis LDP";
    }

    @Override
    public void initialize(final Bootstrap<TrellisConfiguration> bootstrap) {
        // Not currently used
    }

    @Override
    public void run(final TrellisConfiguration config,
                    final Environment environment) throws IOException {

        final String mementoLocation = config.getMementos().getPath();

        final String baseUrl = config.getBaseUrl();

        final IdentifierService idService = new UUIDGenerator();

        final MementoService mementoService = new FileMementoService(mementoLocation);
        final Optional<String> location = ofNullable(config.getRdfstore()).map(RdfConnectionConfiguration::getLocation);

        final RDFConnection rdfConnection;
        if (!location.isPresent()) {
            // in-memory
            rdfConnection = connect(createTxnMem());
        } else {
            final String loc = location.get();
            if (loc.startsWith("http://") || loc.startsWith("https://")) {
                // Remote
                rdfConnection = connect(loc);
            } else {
                // TDB2
                rdfConnection = connect(wrap(connectDatasetGraph(loc)));
            }
        }

        final ResourceService resourceService = new TriplestoreResourceService(rdfConnection, idService,
                mementoService, null);

        final NamespaceService namespaceService = new NamespacesJsonContext(config.getNamespaces().getFile());

        final BinaryService binaryService = new FileBinaryService(config.getBinaries().getPath(),
                idService.getSupplier("file:", config.getBinaries().getLevels(), config.getBinaries().getLength()));

        // IO Service
        final CacheService<String, String> profileCache = new TrellisCache<>(newBuilder()
                .maximumSize(config.getJsonLdCacheSize())
                .expireAfterAccess(config.getJsonLdCacheExpireHours(), HOURS).build());
        final IOService ioService = new JenaIOService(namespaceService, TrellisUtils.getAssetConfiguration(config),
                config.getJsonLdWhitelist(), config.getJsonLdDomainWhitelist(), profileCache);

        // Health checks
        environment.healthChecks().register("rdfconnection", new RDFConnectionHealthCheck(rdfConnection));

        getAuthFilters(config).ifPresent(filters -> environment.jersey().register(new ChainedAuthFilter<>(filters)));

        // Resource matchers
        environment.jersey().register(new LdpResource(resourceService, ioService, binaryService, baseUrl));

        // Filters
        environment.jersey().register(new AgentAuthorizationFilter(new SimpleAgent(), emptyList()));
        environment.jersey().register(new CacheControlFilter(config.getCacheMaxAge()));

        // Authorization
        getWebacConfiguration(config).ifPresent(webacCache ->
            environment.jersey().register(new WebAcFilter(
                        asList("Authorization"), new WebACService(resourceService, webacCache))));

        // CORS
        getCorsConfiguration(config).ifPresent(cors -> environment.jersey().register(
                new CrossOriginResourceSharingFilter(cors.getAllowOrigin(), cors.getAllowMethods(),
                    cors.getAllowHeaders(), cors.getExposeHeaders(), cors.getAllowCredentials(), cors.getMaxAge())));
    }
}