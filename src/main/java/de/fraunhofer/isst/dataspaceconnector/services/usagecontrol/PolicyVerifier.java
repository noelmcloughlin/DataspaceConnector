package de.fraunhofer.isst.dataspaceconnector.services.usagecontrol;

import de.fraunhofer.iais.eis.Contract;
import de.fraunhofer.iais.eis.Rule;
import de.fraunhofer.isst.dataspaceconnector.services.HttpUtils;
import de.fraunhofer.isst.dataspaceconnector.services.communication.MessageService;
import de.fraunhofer.isst.ids.framework.exceptions.HttpClientException;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.Duration;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;

/**
 * This class provides access permission information for the {@link de.fraunhofer.isst.dataspaceconnector.services.usagecontrol.PolicyHandler}
 * depending on the policy content.
 */
@Component
public class PolicyVerifier {

    public static final Logger LOGGER = LoggerFactory.getLogger(PolicyVerifier.class);

    private PolicyReader policyReader;
    private MessageService messageService;
    private HttpUtils httpUtils;

    @Autowired
    /**
     * Constructor for PolicyVerifier.
     *
     */
    public PolicyVerifier(PolicyReader policyReader, MessageService messageService,
        HttpUtils httpUtils) {
        this.policyReader = policyReader;
        this.messageService = messageService;
        this.httpUtils = httpUtils;
    }

    /**
     * Allows data access.
     *
     * @return Access allowed.
     */
    public boolean allowAccess() {
        return true;
    }

    /**
     * Inhibits data access.
     *
     * @return Access denied.
     */
    public boolean inhibitAccess() {
        return false;
    }

    /**
     * Saves the access date into the database.
     *
     * @return Success or not (access or inhibition).
     */
    public boolean logAccess() {
        try {
            Response response = messageService.sendLogMessage();
            if (response != null && response.code() == 200) {
                return allowAccess();
            } else {
                LOGGER.error("NOT LOGGED");
                return allowAccess();
            }
        } catch (HttpClientException | IOException e) {
            LOGGER.error(e.getMessage());
            return inhibitAccess();
        }
    }

    /**
     * Notify participant about data access.
     *
     * @param contract a {@link de.fraunhofer.iais.eis.Contract} object.
     * @return Success or not (access or inhibition).
     */
    public boolean sendNotification(Contract contract) {
        Rule rule = contract.getPermission().get(0).getPostDuty().get(0);
        String recipient = policyReader.getEndpoint(rule);

        try {
            Response response = messageService.sendNotificationMessage(recipient);
            if (response != null && response.code() == 200) {
                return allowAccess();
            } else {
                LOGGER.error("NOT NOTIFIED");
                return allowAccess();
            }
        } catch (HttpClientException | IOException e) {
            LOGGER.error(e.getMessage());
            return inhibitAccess();
        }
    }

    /**
     * Checks if the requested access is in the allowed time interval.
     *
     * @param contract a {@link de.fraunhofer.iais.eis.Contract} object.
     * @return If this is the case, access is provided.
     */
    public boolean checkInterval(Contract contract) {
        PolicyReader.TimeInterval timeInterval = policyReader
            .getTimeInterval(contract.getPermission().get(0));
        Date date = new Date();

        if (date.after(timeInterval.getStart()) && date.before(timeInterval.getEnd())) {
            return allowAccess();
        } else {
            return inhibitAccess();
        }
    }

    /**
     * Checks whether the current date is later than the specified one.
     *
     * @param dateNow   The current date.
     * @param maxAccess The target date.
     * @return True if the date has been already exceeded, false if not.
     */
    public boolean checkDate(Date dateNow, Date maxAccess) {
        return dateNow.after(maxAccess);
    }

    /**
     * Adds a duration to a date to get the a date.
     *
     * @param created  The date when the resource was created.
     * @param contract a {@link de.fraunhofer.iais.eis.Contract} object.
     * @return True if the resource should be deleted, false if not.
     */
    public boolean checkDuration(Date created, Contract contract) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(created);
        try {
            Duration duration = policyReader.getDuration(contract.getPermission().get(0));

            cal.add(Calendar.SECOND, duration.getSeconds());
            cal.add(Calendar.MINUTE, duration.getMinutes());
            cal.add(Calendar.HOUR_OF_DAY, duration.getHours());
            cal.add(Calendar.DAY_OF_MONTH, duration.getDays());
            cal.add(Calendar.MONTH, duration.getMonths());
            cal.add(Calendar.YEAR, duration.getYears());

            return !checkDate(new Date(), cal.getTime());
        } catch (DatatypeConfigurationException e) {
            return inhibitAccess();
        }
    }

    /**
     * Checks whether the maximum of access number is already reached.
     *
     * @param contract a {@link de.fraunhofer.iais.eis.Contract} object.
     * @param uuid     a {@link java.util.UUID} object.
     * @return If this is not the case, access is provided. Otherwise, data is deleted and access
     * denied.
     */
    public boolean checkFrequency(Contract contract, UUID uuid) {
        int max = policyReader.getMaxAccess(contract.getPermission().get(0));
        URI pip = policyReader.getPipEndpoint(contract.getPermission().get(0));

        try {
            String accessed = httpUtils
                .sendHttpsGetRequestWithBasicAuth(pip + uuid.toString() + "/access", "admin",
                    "password");
            if (Integer.parseInt(accessed) > max) {
                return inhibitAccess();
            } else {
                return allowAccess();
            }
        } catch (IOException | RuntimeException e) {
            return inhibitAccess();
        }
    }

    /**
     * Checks if the duration since resource creation or the max date for resource access has been
     * already exceeded.
     *
     * @param rule a {@link de.fraunhofer.iais.eis.Rule} object.
     * @return True if the resource should be deleted, false if not.
     * @throws java.text.ParseException if any.
     */
    public boolean checkForDelete(Rule rule) throws ParseException {
        Date max = policyReader.getDate(rule);
        if (max != null) {
            return checkDate(new Date(), max);
        } else {
            return false;
        }
    }
}
