/**
 * This file Copyright (c) 2020-2024 Magnolia International
 * Ltd.  (http://www.magnolia-cms.com). All rights reserved.
 *
 *
 * This file is dual-licensed under both the Magnolia
 * Network Agreement and the GNU General Public License.
 * You may elect to use one or the other of these licenses.
 *
 * This file is distributed in the hope that it will be
 * useful, but AS-IS and WITHOUT ANY WARRANTY; without even the
 * implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE, TITLE, or NONINFRINGEMENT.
 * Redistribution, except as permitted by whichever of the GPL
 * or MNA you select, is prohibited.
 *
 * 1. For the GPL license (GPL), you can redistribute and/or
 * modify this file under the terms of the GNU General
 * Public License, Version 3, as published by the Free Software
 * Foundation.  You should have received a copy of the GNU
 * General Public License, Version 3 along with this program;
 * if not, write to the Free Software Foundation, Inc., 51
 * Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * 2. For the Magnolia Network Agreement (MNA), this file
 * and the accompanying materials are made available under the
 * terms of the MNA which accompanies this distribution, and
 * is available at http://www.magnolia-cms.com/mna.html
 *
 * Any modifications to this file must keep this entire header
 * intact.
 *
 */
package info.magnolia.resources.app.data;

import static info.magnolia.resources.app.workbench.tree.ResourcesTreePresenter.*;

import info.magnolia.config.registry.DefinitionMetadata;
import info.magnolia.config.registry.DefinitionProvider;
import info.magnolia.config.registry.RegistryFacade;
import info.magnolia.jcr.RuntimeRepositoryException;
import info.magnolia.jcr.util.NodeTypes;
import info.magnolia.module.ModuleRegistry;
import info.magnolia.objectfactory.Components;
import info.magnolia.resourceloader.Resource;
import info.magnolia.resourceloader.ResourceOrigin;
import info.magnolia.resourceloader.layered.LayeredResource;
import info.magnolia.ui.datasource.optionlist.Option;
import info.magnolia.ui.filter.DataFilter;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.commons.lang3.StringUtils;

import com.vaadin.data.provider.AbstractBackEndDataProvider;
import com.vaadin.data.provider.Query;

/**
 * Flat data provider used while filtering grid.
 */
public class ResourceFilteringDataProvider extends AbstractBackEndDataProvider<Resource, DataFilter> implements ResourceHelper {

    private final ResourceOrigin<?> resourceOrigin;
    private final Collection<String> moduleFolders;

    private final Map<String, Boolean> shouldBeVisibleResource = new HashMap<>();

    @Inject
    public ResourceFilteringDataProvider(ResourceOrigin<?> resourceOrigin, ModuleRegistry moduleRegistry, RegistryFacade registryFacade) {
        this.resourceOrigin = resourceOrigin;
        this.moduleFolders = new HashSet<>(moduleRegistry.getModuleNames()); //include all java modules (including those without any definitions, but might have e.g. CSS files)
        final Collection<DefinitionProvider<?>> definitionProviders = registryFacade.query().findMultiple(); //add all modules (including light modules)
        definitionProviders
                .stream()
                .map(DefinitionProvider::getMetadata)
                .map(DefinitionMetadata::getModule)
                .forEach(moduleFolders::add);
    }

    /**
     * @deprecated since 3.0.9 use {@link #ResourceFilteringDataProvider(ResourceOrigin, ModuleRegistry, RegistryFacade)} instead.
     */
    @Deprecated
    public ResourceFilteringDataProvider(ResourceOrigin<?> resourceOrigin) {
        this(resourceOrigin, Components.getComponent(ModuleRegistry.class), Components.getComponent(RegistryFacade.class));
    }

    @Override
    public Object getId(Resource item) {
        return item.getPath();
    }

    @Override
    protected Stream<Resource> fetchFromBackEnd(Query<Resource, DataFilter> query) {
        shouldBeVisibleResource.clear();
        return resourceOrigin.find("/", resource -> query.getFilter()
                .map(dataFilter -> applyFilter(resource, dataFilter))
                .orElse(true))
                .skip(query.getOffset())
                .limit(query.getLimit());
    }

    private boolean applyFilter(Resource resource, DataFilter filter) {
        return shouldBeVisible(resource) && filter.getPropertyFilters().entrySet().stream()
                .filter(stringObjectEntry -> !"".equals(stringObjectEntry.getValue())) //empty text filter
                .allMatch(stringObjectEntry -> filter(stringObjectEntry.getValue(), resource, stringObjectEntry.getKey()));
    }

    private boolean filter(Object filterValue, Resource resource, String propertyName) {
        if (filterValue == null) { //empty filter
            return true;
        } else if (COLUMN_ORIGIN.equals(propertyName)) {
            return filterByOrigin((ResourceOrigin<?>) filterValue, resource);
        } else if (COLUMN_TYPE.equals(propertyName)) {
            return StringUtils.contains(TIKA.detect(resource.getName()), (String) filterValue);
        } else if (COLUMN_NAME.equals(propertyName)) {
            return resource.getName().contains((String) filterValue);
        } else if (COLUMN_OVERRIDDEN.equals(propertyName)) {
            return !((Boolean) filterValue) || (resource instanceof LayeredResource && ((LayeredResource) resource).getLayers().size() > 1);
        } else if (COLUMN_STATUS.equals(propertyName)) {
            int expectedStatus = Integer.parseInt(((Option) filterValue).getValue());
            return getJcrNode(resource)
                    .map(this::getActivationStatus)
                    .map(integer -> integer == expectedStatus)
                    .orElse(false);
        }
        throw new IllegalArgumentException("Unsupported filter property: " + propertyName);
    }

    private boolean filterByOrigin(ResourceOrigin<?> filterValue, Resource resource) {
        List<Resource> resources = resource instanceof LayeredResource ? ((LayeredResource) resource).getLayers() : Arrays.asList(resource);
        return resources.stream()
                .map(Resource::getOrigin)
                .anyMatch(origin -> origin.getClass().equals(filterValue.getClass()));
    }

    private int getActivationStatus(Node node) {
        try {
            return NodeTypes.Activatable.getActivationStatus(node);
        } catch (RepositoryException e) {
            throw new RuntimeRepositoryException(e);
        }
    }

    @Override
    protected int sizeInBackEnd(Query<Resource, DataFilter> query) {
        return (int) fetchFromBackEnd(query).count();
    }

    private boolean shouldBeVisible(Resource resource) {
        String topLevelParentName = StringUtils.substringBetween(resource.getPath(), "/");
        if (topLevelParentName != null) {
            if (shouldBeVisibleResource.containsKey(topLevelParentName)) {
                return shouldBeVisibleResource.get(topLevelParentName);
            }

            Resource topLevelResource = resolveTopLevelResource(resource);
            boolean isModuleFolderOrIsNotClasspathOnlyResources = isModuleFolderOrIsNotClasspathOnlyResources(topLevelResource, moduleFolders);

            shouldBeVisibleResource.put(topLevelParentName, isModuleFolderOrIsNotClasspathOnlyResources);
            return isModuleFolderOrIsNotClasspathOnlyResources;
        }
        return isModuleFolderOrIsNotClasspathOnlyResources(resource, moduleFolders);
    }

    private Resource resolveTopLevelResource(Resource resource) {
        Resource topLevelResource = resource.getParent();
        while (!resourceOrigin.getRoot().getPath().equals(topLevelResource.getParent().getPath())) {
            topLevelResource = topLevelResource.getParent();
        }
        return topLevelResource;
    }
}
