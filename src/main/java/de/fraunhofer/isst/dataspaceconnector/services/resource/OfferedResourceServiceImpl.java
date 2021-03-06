package de.fraunhofer.isst.dataspaceconnector.services.resource;

import de.fraunhofer.iais.eis.*;
import de.fraunhofer.iais.eis.util.TypedLiteral;
import de.fraunhofer.iais.eis.util.Util;
import de.fraunhofer.isst.dataspaceconnector.exceptions.*;
import de.fraunhofer.isst.dataspaceconnector.model.OfferedResource;
import de.fraunhofer.isst.dataspaceconnector.model.ResourceMetadata;
import de.fraunhofer.isst.dataspaceconnector.model.ResourceRepresentation;
import de.fraunhofer.isst.dataspaceconnector.services.HttpUtils;
import de.fraunhofer.isst.dataspaceconnector.services.IdsUtils;
import de.fraunhofer.isst.dataspaceconnector.services.UUIDUtils;
import java.util.stream.Collectors;
import org.apache.commons.lang3.NotImplementedException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.MalformedURLException;
import java.util.*;

/**
 * This class implements all methods of {@link OfferedResourceService}. It provides database
 * resource handling for all offered resources.
 */
@Service
public class OfferedResourceServiceImpl implements OfferedResourceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OfferedResourceService.class);

    private final OfferedResourceRepository offeredResourceRepository;
    private final HttpUtils httpUtils;
    private final IdsUtils idsUtils;
    private final ContractOffer contractOffer;

    /**
     * Constructor for OfferedResourceServiceImpl.
     */
    @Autowired
    public OfferedResourceServiceImpl(@NotNull OfferedResourceRepository offeredResourceRepository,
        @NotNull HttpUtils httpUtils, @NotNull IdsUtils idsUtils) {
        this.offeredResourceRepository = offeredResourceRepository;
        this.httpUtils = httpUtils;
        this.idsUtils = idsUtils;

        contractOffer = new ContractOfferBuilder()
            ._permission_(Util.asList(new PermissionBuilder()
                ._title_(Util.asList(new TypedLiteral("Example Usage Policy")))
                ._description_(Util.asList(new TypedLiteral("provide-access")))
                ._action_(Util.asList(Action.USE))
                .build()))
            .build();
    }

    /**
     * Returns the resource list.
     */
    @Override
    public List<Resource> getResourceList() {
        return getAllResources().parallelStream().map(idsUtils::getAsResource)
            .collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<UUID, Resource> getOfferedResources() {
        return getAllResources().parallelStream().collect(Collectors
            .toMap(OfferedResource::getUuid, idsUtils::getAsResource));
    }

    /**
     * Saves the resources with its metadata as external resource or internal resource.
     *
     * @throws InvalidResourceException - if the resource is not valid.
     * @throws ResourceAlreadyExists    - if the resource does already exists.
     * @throws ResourceException        - if the resource could not be created. exists.
     */
    @Override
    public UUID addResource(ResourceMetadata resourceMetadata) throws ResourceException {
        try {
            final var uuid = UUIDUtils.createUUID((UUID x) -> {
                try {
                    return getResource(x) != null;
                } catch (InvalidResourceException exception) {
                    return false;
                }
            });

            addResourceWithId(resourceMetadata, uuid);
            return uuid;
        } catch (UUIDFormatException exception) {
            throw new ResourceException("Failed to create resource.", exception);
        }
    }

    /**
     * @throws InvalidResourceException - if the resource is not valid.
     * @throws ResourceAlreadyExists    - if the resource does already exists.
     */
    @Override
    public void addResourceWithId(ResourceMetadata resourceMetadata, UUID uuid) throws
        InvalidResourceException, ResourceAlreadyExists {
        if (getResource(uuid) != null) {
            throw new ResourceAlreadyExists("The resource does already exist.");
        }

        resourceMetadata.setPolicy(contractOffer.toRdf());
        final var resource = new OfferedResource(uuid, new Date(), new Date(), resourceMetadata,
            "");

        storeResource(resource);
    }

    /**
     * Publishes the resource data.
     */
    @Override
    public void addData(UUID resourceId, String data) throws InvalidResourceException,
        ResourceNotFoundException {
        final var resource = getResource(resourceId);
        if (resource == null) {
            throw new ResourceNotFoundException("The resource does not exist.");
        }

        resource.setData(data);
        storeResource(resource);
    }

    /**
     * Updates resource metadata by id.
     */
    @Override
    public void updateResource(UUID resourceId, ResourceMetadata resourceMetadata) throws
        InvalidResourceException, ResourceNotFoundException {
        final var resource = getResource(resourceId);
        if (resource == null) {
            throw new ResourceNotFoundException("The resource does not exist.");
        }

        resource.setResourceMetadata(resourceMetadata);
        storeResource(resource);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateContract(UUID resourceId, String policy) throws ResourceNotFoundException,
        InvalidResourceException {
        final var resourceMetadata = getMetadata(resourceId);

        // NOTE SAFETY CHECK
        resourceMetadata.setPolicy(policy);
        updateResource(resourceId, resourceMetadata);
    }

    /**
     * Deletes a resource by id.
     */
    @Override
    public boolean deleteResource(UUID resourceId) {
        offeredResourceRepository.deleteById(resourceId);
        return true;
    }

    /**
     * Gets a resource by id.
     */
    @Override
    public OfferedResource getResource(UUID resourceId) throws InvalidResourceException {
        final var resource = offeredResourceRepository.findById(resourceId);

        if (resource.isEmpty()) {
            return null;
        } else {
            invalidResourceGuard(resource.get());
            return resource.get();
        }
    }

    public List<OfferedResource> getAllResources() {
        return offeredResourceRepository.findAll();
    }

    /**
     * Gets resource metadata by id.
     */
    @Override
    public ResourceMetadata getMetadata(UUID resourceId) throws ResourceNotFoundException,
        InvalidResourceException {
        final var resource = getResource(resourceId);
        if (resource == null) {
            throw new ResourceNotFoundException("The resource does not exist.");
        }

        return resource.getResourceMetadata();
    }

    public Map<UUID, ResourceRepresentation> getAllRepresentations(UUID resourceId) throws
        ResourceNotFoundException, InvalidResourceException {
        return getMetadata(resourceId).getRepresentations();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ResourceRepresentation getRepresentation(UUID resourceId, UUID representationId) throws
        ResourceNotFoundException, InvalidResourceException {
        return getAllRepresentations(resourceId).get(representationId);
    }

    /**
     * Gets data from local database.
     */
    @Override
    public String getData(UUID resourceId) throws ResourceNotFoundException,
        InvalidResourceException, ResourceException {
        final var representations = getAllRepresentations(resourceId);
        for (var representationId : representations.keySet()) {
            try {
                return getDataByRepresentation(resourceId, representationId);
            } catch (ResourceException exception) {
                // The resource is incomplete or wrong.
                LOGGER.warn("Resource exception.");
            } catch (RuntimeException exception) {
                // The resource could not be received.
                LOGGER.warn("Failed to get resource data.");
            }
        }

        // This code should never be reached since the representation should have at least one
        // representation.
        invalidResourceGuard(getResource(resourceId));
        // Add a runtime exception in case the resource valid logic changed.
        throw new RuntimeException("This code should not have been reached.");
    }

    /**
     * Gets data from local or external data source.
     */
    @Override
    public String getDataByRepresentation(UUID resourceId, UUID representationId) throws
        InvalidResourceException, ResourceNotFoundException, ResourceException {
        final var resource = getResource(resourceId);
        if (resource == null) {
            throw new ResourceNotFoundException("The resource does not exist.");
        }

        final var representation = getRepresentation(resourceId, representationId);
        if (representation == null) {
            throw new ResourceNotFoundException("The resource representation does not exist.");
        }

        return getDataString(resource, representation);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public UUID addRepresentation(UUID resourceId, ResourceRepresentation representation) throws
        ResourceNotFoundException, InvalidResourceException, ResourceAlreadyExists {
        final var uuid = UUIDUtils.createUUID(
            (UUID x) -> {
                try {
                    return getRepresentation(resourceId, x) != null;
                } catch (InvalidResourceException e) {
                    return false;
                }
            });

        return addRepresentationWithId(resourceId, representation, uuid);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public UUID addRepresentationWithId(UUID resourceId, ResourceRepresentation representation,
        UUID representationId) throws
        ResourceNotFoundException, InvalidResourceException, ResourceAlreadyExists {
        final var metaData = getMetadata(resourceId);
        if (getRepresentation(resourceId, representationId) != null) {
            throw new ResourceAlreadyExists("The representation does already exist.");
        }

        representation.setUuid(representationId);
        metaData.getRepresentations().put(representation.getUuid(), representation);

        updateResource(resourceId, metaData);
        return representationId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateRepresentation(UUID resourceId, UUID representationId,
        ResourceRepresentation representation) throws
        ResourceNotFoundException, InvalidResourceException {
        if (getRepresentation(resourceId, representationId) != null) {
            representation.setUuid(representationId);
            var representations = getAllRepresentations(resourceId);
            representations.put(representationId, representation);

            var metadata = getMetadata(resourceId);
            metadata.setRepresentations(representations);

            updateResource(resourceId, metadata);
        } else {
            LOGGER.warn(String.format("Tried to update representation %s with resource %s.",
                representationId, resourceId));
            throw new ResourceNotFoundException("The resource representation does not exist.");
        }
    }


    /**
     * {@inheritDoc}
     *
     * @throws ResourceNotFoundException - if the resource could not be found.
     * @throws InvalidResourceException  - if the resource is not valid.
     */
    @Override
    public boolean deleteRepresentation(UUID resourceId, UUID representationId) throws
        ResourceNotFoundException, InvalidResourceException {
        var representations = getAllRepresentations(resourceId);
        if (representations.remove(representationId) != null) {
            var metadata = getMetadata(resourceId);
            metadata.setRepresentations(representations);

            updateResource(resourceId, metadata);
            return true;
        } else {
            LOGGER.warn(String.format("Tried to delete representation %s with resource %s.",
                representationId, resourceId));
            return false;
        }
    }

    public Optional<String> isValidOfferedResource(OfferedResource resource) {
        if (resource == null) {
            return Optional.of("The resource cannot be null.");
        }

        if (resource.getResourceMetadata() == null) {
            return Optional.of("The resource metadata cannot be null.");
        }

        if (resource.getResourceMetadata().getRepresentations() == null) {
            return Optional.of("The resource representation cannot be null.");
        }

        if (resource.getResourceMetadata().getRepresentations().size() < 1) {
            return Optional.of("The resource representation must have at least one element.");
        }

        return Optional.empty();
    }

    /**
     * @param resource
     * @throws InvalidResourceException - if the resource is not valid.
     */
    private void invalidResourceGuard(OfferedResource resource) throws InvalidResourceException {
        final var error = isValidOfferedResource(resource);
        if (error.isPresent()) {
            throw new InvalidResourceException(error.get());
        }
    }

    /**
     * @param resource
     * @throws InvalidResourceException - if the resource is not valid.
     */
    private void storeResource(OfferedResource resource) throws InvalidResourceException {
        final var error = isValidOfferedResource(resource);
        if (error.isPresent()) {
            throw new InvalidResourceException("Not a valid resource. " + error.get());
        }

        offeredResourceRepository.save(resource);
    }

    /**
     * Gets data as string.
     *
     * @param resource       The connector resource object.
     * @param representation The representation.
     * @return The string or an exception.
     * @throws ResourceException - if the resource source is not defined or source url is
     *                           ill-formatted.
     */
    private String getDataString(OfferedResource resource, ResourceRepresentation representation)
        throws ResourceException {
        if (representation.getSource() != null) {
            try {
                final var address = representation.getSource().getUrl();
                final var username = representation.getSource().getUsername();
                final var password = representation.getSource().getPassword();

                switch (representation.getSource().getType()) {
                    case LOCAL:
                        return resource.getData();
                    case HTTP_GET:
                        return httpUtils.sendHttpGetRequest(address.toString());
                    case HTTPS_GET:
                        return httpUtils.sendHttpsGetRequest(address.toString());
                    case HTTPS_GET_BASICAUTH:
                        return httpUtils
                            .sendHttpsGetRequestWithBasicAuth(address.toString(), username,
                                password);
                    default:
                        // This exception is only thrown when BackendSource.Type is expanded but this
                        // switch is not
                        throw new NotImplementedException("This type is not supported");
                }
            } catch (MalformedURLException exception) {
                // One of the http requests received a non url as address
                LOGGER.error("The resource representation is not an url.", exception);
                throw new ResourceException("The resource source representation is not an url.",
                    exception);
            } catch (RuntimeException exception) {
                // One of the http calls encountered problems.
                throw new ResourceException("The resource could not be found.", exception);
            }
        } else {
            throw new ResourceException("The resource has no defined backend.");
        }
    }
}
