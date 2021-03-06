package org.apereo.cas.support.oauth.authenticator;

import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.CasProtocolConstants;
import org.apereo.cas.authentication.Authentication;
import org.apereo.cas.authentication.AuthenticationBuilder;
import org.apereo.cas.authentication.BasicCredentialMetaData;
import org.apereo.cas.authentication.BasicIdentifiableCredential;
import org.apereo.cas.authentication.CredentialMetaData;
import org.apereo.cas.authentication.DefaultAuthenticationBuilder;
import org.apereo.cas.authentication.DefaultHandlerResult;
import org.apereo.cas.authentication.HandlerResult;
import org.apereo.cas.authentication.principal.Principal;
import org.apereo.cas.authentication.principal.PrincipalFactory;
import org.apereo.cas.authentication.principal.Service;
import org.apereo.cas.authentication.principal.ServiceFactory;
import org.apereo.cas.authentication.principal.WebApplicationService;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.support.oauth.OAuth20Constants;
import org.apereo.cas.support.oauth.profile.OAuth20ProfileScopeToAttributesFilter;
import org.apereo.cas.support.oauth.services.OAuthRegisteredService;
import org.pac4j.core.context.J2EContext;
import org.pac4j.core.profile.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;

/**
 * This is {@link OAuth20CasAuthenticationBuilder}.
 *
 * @author Misagh Moayyed
 * @since 5.1.0
 */
public class OAuth20CasAuthenticationBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(OAuth20CasAuthenticationBuilder.class);

    /**
     * Collection of CAS settings.
     */
    protected final CasConfigurationProperties casProperties;

    /**
     * The Principal factory.
     */
    protected final PrincipalFactory principalFactory;
    /**
     * The Web application service service factory.
     */
    protected final ServiceFactory<WebApplicationService> webApplicationServiceServiceFactory;

    /**
     * Convert profile scopes to attributes.
     */
    protected final OAuth20ProfileScopeToAttributesFilter scopeToAttributesFilter;

    public OAuth20CasAuthenticationBuilder(final PrincipalFactory principalFactory,
                                           final ServiceFactory<WebApplicationService> webApplicationServiceServiceFactory,
                                           final OAuth20ProfileScopeToAttributesFilter scopeToAttributesFilter,
                                           final CasConfigurationProperties casProperties) {
        this.principalFactory = principalFactory;
        this.webApplicationServiceServiceFactory = webApplicationServiceServiceFactory;
        this.scopeToAttributesFilter = scopeToAttributesFilter;
        this.casProperties = casProperties;
    }

    /**
     * Build service.
     *
     * @param registeredService the registered service
     * @param context           the context
     * @param useServiceHeader  the use service header
     * @return the service
     */
    public Service buildService(final OAuthRegisteredService registeredService, final J2EContext context, final boolean useServiceHeader) {
        String id = null;
        if (useServiceHeader) {
            id = context.getRequestHeader(CasProtocolConstants.PARAMETER_SERVICE);
            if (StringUtils.isBlank(id)) {
                id = context.getRequestHeader("X-".concat(CasProtocolConstants.PARAMETER_SERVICE));
            }
            LOGGER.debug("Located service based on request header is [{}]", id);
        }
        if (StringUtils.isBlank(id)) {
            id = registeredService.getClientId();
        }
        return webApplicationServiceServiceFactory.createService(id);
    }

    /**
     * Create an authentication from a user profile.
     *
     * @param profile           the given user profile
     * @param registeredService the registered service
     * @param context           the context
     * @param service           the service
     * @return the built authentication
     */
    public Authentication build(final UserProfile profile,
                                final OAuthRegisteredService registeredService,
                                final J2EContext context,
                                final Service service) {
        final Principal newPrincipal =
                this.scopeToAttributesFilter.filter(service,
                        this.principalFactory.createPrincipal(profile.getId(), profile.getAttributes()),
                        registeredService,
                        context);

        LOGGER.debug("Created final principal [{}] after filtering attributes based on [{}]", newPrincipal, registeredService);

        final String authenticator = profile.getClass().getCanonicalName();
        final CredentialMetaData metadata = new BasicCredentialMetaData(new BasicIdentifiableCredential(profile.getId()));
        final HandlerResult handlerResult = new DefaultHandlerResult(authenticator, metadata, newPrincipal, new ArrayList<>());

        final String state = StringUtils.defaultIfBlank(context.getRequestParameter(OAuth20Constants.STATE), StringUtils.EMPTY);
        final String nonce = StringUtils.defaultIfBlank(context.getRequestParameter(OAuth20Constants.NONCE), StringUtils.EMPTY);
        LOGGER.debug("OAuth [{}] is [{}], and [{}] is [{}]", OAuth20Constants.STATE, state, OAuth20Constants.NONCE, nonce);

        final AuthenticationBuilder bldr = DefaultAuthenticationBuilder.newInstance()
                .addAttribute("permissions", profile.getPermissions())
                .addAttribute("roles", profile.getRoles())
                .addAttribute(OAuth20Constants.STATE, state)
                .addAttribute(OAuth20Constants.NONCE, nonce)
                .addCredential(metadata)
                .setPrincipal(newPrincipal)
                .setAuthenticationDate(ZonedDateTime.now())
                .addSuccess(profile.getClass().getCanonicalName(), handlerResult);

        // Add "other" profile attributes as authentication attributes.
        if (casProperties.getAuthn().getOauth().getAccessToken().isReleaseProtocolAttributes()) {
            profile.getAttributes().forEach((k, v) -> {
                if (!newPrincipal.getAttributes().containsKey(k)) {
                    LOGGER.debug("Added attribute [{}] with value [{}] to the authentication", k, v);
                    bldr.addAttribute(k, v);
                } else {
                    LOGGER.debug("Skipped over attribute [{}] since it's already contained by the principal", k);
                }
            });
        }
        return bldr.build();
    }
}
