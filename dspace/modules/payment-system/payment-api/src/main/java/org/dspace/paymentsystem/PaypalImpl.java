/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.paymentsystem;

import edu.harvard.hul.ois.mets.helper.DateTime;
import org.apache.cocoon.environment.Request;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.log4j.Logger;
import org.dspace.app.util.SubmissionInfo;
import org.dspace.app.xmlui.aspect.submission.FlowUtils;
import org.dspace.app.xmlui.utils.HandleUtil;
import org.dspace.app.xmlui.wing.Message;
import org.dspace.app.xmlui.wing.WingException;
import org.dspace.app.xmlui.wing.element.*;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.AuthorizeManager;
import org.dspace.content.*;
import org.dspace.content.Item;
import org.dspace.core.*;
import org.dspace.eperson.EPerson;
import org.dspace.storage.rdbms.DatabaseManager;
import org.dspace.storage.rdbms.TableRow;
import org.dspace.submit.AbstractProcessingStep;
import org.dspace.utils.DSpace;
import org.dspace.workflow.WorkflowItem;

import javax.mail.MessagingException;
import javax.servlet.http.HttpServletRequest;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.net.URLEncoder;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Random;

import org.dspace.app.xmlui.wing.Message;

/**
 *  Paypal Service for interacting with Payflow Pro API
 *
 * @author Mark Diggory, mdiggory at atmire.com
 * @author Fabio Bolognesi, fabio at atmire.com
 * @author Lantian Gai, lantian at atmire.com
 */
public class PaypalImpl implements PaypalService{

    protected Logger log = Logger.getLogger(PaypalImpl.class);

    public String getSecureTokenId(){
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmssSSSSSSSSSS");
       return sdf.format(new Date());


        //return DigestUtils.md5Hex(new Date().toString()); //"9a9ea8208de1413abc3d60c86cb1f4c5";
    }

    //generate a secure token from paypal
    public String generateSecureToken(ShoppingCart shoppingCart,String secureTokenId,String itemID, String type){
        String secureToken=null;
        String requestUrl = ConfigurationManager.getProperty("payment-system","paypal.payflow.link");

        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            javax.xml.parsers.DocumentBuilder db = dbf.newDocumentBuilder();
            PostMethod post = new PostMethod(requestUrl);

            post.addParameter("SECURETOKENID",secureTokenId);
            post.addParameter("CREATESECURETOKEN","Y");
            post.addParameter("MODE",ConfigurationManager.getProperty("payment-system","paypal.mode"));
            post.addParameter("PARTNER",ConfigurationManager.getProperty("payment-system","paypal.partner"));

            post.addParameter("VENDOR",ConfigurationManager.getProperty("payment-system","paypal.vendor"));
            post.addParameter("USER",ConfigurationManager.getProperty("payment-system","paypal.user"));
            post.addParameter("PWD", ConfigurationManager.getProperty("payment-system","paypal.pwd"));
            //get.addParameter("RETURNURL", URLEncoder.encode("http://us.atmire.com:8080/submit-paypal-checkout"));
            if(ConfigurationManager.getProperty("payment-system","paypal.returnurl").length()>0)
            post.addParameter("RETURNURL", ConfigurationManager.getProperty("payment-system","paypal.returnurl"));
            post.addParameter("TENDER", "C");
            post.addParameter("TRXTYPE", type);
            if(type.equals("S")){
                //generate reauthorization form
                post.addParameter("AMT", Double.toString(shoppingCart.getTotal()));
            }
            else
            {
                //generate reference transaction form for a later charge
                post.addParameter("AMT", "0.00");
            }
            //TODO:add currency from shopping cart
            post.addParameter("CURRENCY", shoppingCart.getCurrency());
	    log.debug("paypal request URL " + post);
            switch (new HttpClient().executeMethod(post)) {
                case 200:
                case 201:
                case 202:
                    String string = post.getResponseBodyAsString();
                    String[] results = string.split("&");
                    for(String temp:results)
                    {
                        String[] result = temp.split("=");
                        if(result[0].contains("RESULT")&&!result[1].equals("0"))
                        {
                            //failed to get a secure token
                            log.error("Failed to get a secure token from paypal:"+string);
                            log.error("Failed to get a secure token from paypal:"+post);
                            break;
                        }
                        if(result[0].equals("SECURETOKEN"))
                        {
                            secureToken=result[1];
                        }
                    }


                    break;
                default:
                    log.error("get paypal secure token error");
            }

            post.releaseConnection();
        }
        catch (Exception e) {
            log.error("get paypal secure token error:",e);
            return null;
        }

        return secureToken;
    }
    //charge the credit card stored as a reference transaction
    public boolean submitReferenceTransaction(Context c,WorkflowItem wfi,HttpServletRequest request){

        try{
            PaymentSystemService paymentSystemService = new DSpace().getSingletonService(PaymentSystemService.class);
            ShoppingCart shoppingCart = paymentSystemService.getShoppingCartByItemId(c,wfi.getItem().getID());
            if(shoppingCart.getStatus().equals(ShoppingCart.STATUS_COMPLETED)){
                //this shopping cart has already been charged
                return true;
            }
            Voucher voucher = Voucher.findById(c,shoppingCart.getVoucher());

	    // check whether we're using the special voucher that simulates "payment failed"
            if(voucher!=null&&ConfigurationManager.getProperty("payment-system","paypal.failed.voucher")!=null)
            {
                String failedVoucher = ConfigurationManager.getProperty("payment-system","paypal.failed.voucher");
                 if(voucher.getCode().equals(failedVoucher)||voucher.getStatus().equals(Voucher.STATUS_USED))
                 {
                     log.debug("problem: 'payment failed' voucher has been used, rejecting payment");
                     sendPaymentErrorEmail(c, wfi, shoppingCart, "problem: voucher has been used, rejecting payment");
                     return false;
                 }
            }

            if(shoppingCart.getTotal()==0)
            {
                log.debug("shopping cart total is 0, not charging card");
                sendPaymentWaivedEmail(c, wfi, shoppingCart);
                //if the total is 0 , don't charge
                return true;
            }
            else
            {
                log.debug("charging card");
                return chargeCard(c, wfi, request,shoppingCart);
            }

        }catch (Exception e)
        {
            sendPaymentErrorEmail(c, wfi, null, "exception when submitting reference transaction " + e.getMessage());
            log.error("exception when submiting reference transaction ", e);
        }
        return false;
    }

    public boolean getReferenceTransaction(Context context,WorkflowItem workItem,HttpServletRequest request){
        //return verifyCreditCard
        verifyCreditCard(context,workItem.getItem(),request);
        //todo:debug to be true
        return true;
    }
    public boolean getReferenceTransaction(Context context,WorkspaceItem workItem,HttpServletRequest request){
        //return verifyCreditCard
        verifyCreditCard(context,workItem.getItem(),request);
        //todo:debug to be true
        return true;
    }

    //generate a reference transaction from paypal
    public boolean verifyCreditCard(Context context,Item item, HttpServletRequest request){


        ShoppingCart shoppingCart=null;

        String referenceId=null;
        String cardNumber = request.getParameter("CREDITCARD");
        String CVV2 = request.getParameter("CVV2");
        String expDate = request.getParameter("EXPDATE");
        String firstName = request.getParameter("BILLTOFIRSTNAME");
        String lastName = request.getParameter("BILLTOLASTNAME");
        String street = request.getParameter("BILLTOSTREET");
        String city = request.getParameter("BILLTOCITY");
        String country = request.getParameter("BILLTOCOUNTRY");
        String state = request.getParameter("BILLTOSTATE");
        String zip = request.getParameter("BILLTOZIP");

        String requestUrl = ConfigurationManager.getProperty("payment-system","paypal.link");
        try {
            String secureToken=request.getParameter("SECURETOKEN");
            String secureTokenId=request.getParameter("SECURETOKENID");
            PaymentSystemService paymentSystemService =  new DSpace().getSingletonService(PaymentSystemService.class);
            shoppingCart= paymentSystemService.getShoppingCartByItemId(context,item.getID());


            if(secureToken!=null&&secureTokenId!=null&&shoppingCart!=null){
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                javax.xml.parsers.DocumentBuilder db = dbf.newDocumentBuilder();
                PostMethod post = new PostMethod(requestUrl);

                post.addParameter("SECURETOKENID",secureTokenId);
                post.addParameter("SECURETOKEN",secureToken);

                post.addParameter("SILENTTRAN",ConfigurationManager.getProperty("payment-system","paypal.slienttran"));

                post.addParameter("PARTNER",ConfigurationManager.getProperty("payment-system","paypal.partner"));
                post.addParameter("VENDOR",ConfigurationManager.getProperty("payment-system","paypal.vendor"));
                post.addParameter("USER",ConfigurationManager.getProperty("payment-system","paypal.user"));
                post.addParameter("PWD", ConfigurationManager.getProperty("payment-system","paypal.pwd"));
                //setup the reference transaction
                post.addParameter("TENDER", "C");
                post.addParameter("TRXTYPE", "A");
                post.addParameter("VERBOSITY", ConfigurationManager.getProperty("payment-system","paypal.verbosity"));
                post.addParameter("AMT", "0.00");
                post.addParameter("CREDITCARD",cardNumber);
                post.addParameter("CVV2",CVV2);
                post.addParameter("EXPDATE",expDate);
                post.addParameter("BILLTOFIRSTNAME",firstName);
                post.addParameter("BILLTOLASTNAME",lastName);
                post.addParameter("BILLTOSTREET",street);
                post.addParameter("BILLTOSTATE",state);
                post.addParameter("BILLTOCITY",city);
                post.addParameter("BILLTOCOUNTRY",country);
                post.addParameter("BILLTOZIP",zip);


                //TODO:add currency from shopping cart
                post.addParameter("CURRENCY", shoppingCart.getCurrency());
		log.debug("paypal transaction url " + post);
		int httpStatus = new HttpClient().executeMethod(post);
                switch (httpStatus) {
                    case 200:
                    case 201:
                    case 202:
                        String string = post.getResponseBodyAsString();
			log.debug("paypal response = " + string);
                        String[] results = string.split("&");
                        for(String temp:results)
                        {
                            String[] result = temp.split("=");
                            //TODO: ignore the error from paypal server, add the error check after
                            //figure out the correct way to process the credit card info
//                        if(result[0].contains("RESULT")&&!result[1].equals("0"))
//                        {
                            //failed to pass the credit card authorization check
//                            log.error("Failed to get a reference transaction from paypal:"+string);
//                            log.error("Failed to get a reference transaction from paypal:"+get);
//                            return false;
//                        }
                            //always return true so we can go through the workflow
                            if(result[0].contains("PNREF"))
                            {
                                shoppingCart.setTransactionId(result[1]);
                                shoppingCart.update();

                                return true;
                            }
                        }
                        break;
                    default:
                        log.error("unexpected code getting paypal reference transaction: " + httpStatus + ", " + post.getResponseBodyAsString() );
                        return false;
                }

                post.releaseConnection();
            }
            else{
                log.error("get paypal reference transaction error or shopping cart error:"+secureToken+secureTokenId+shoppingCart);
                return false;
            }
        }
        catch (Exception e) {
            log.error("get paypal reference transaction:", e);
            return false;
        }
        return false;
    }



    @Override
    public boolean chargeCard(Context c, WorkflowItem wfi, HttpServletRequest request, ShoppingCart shoppingCart) {
        //this method should get the reference code and submit it to paypal to do the actural charge process

        if(shoppingCart.getTransactionId()==null){
            String transactionIdAbsentError = "transaction id absent, cannot charge card";
            log.debug(transactionIdAbsentError);
            sendPaymentErrorEmail(c, wfi, shoppingCart, transactionIdAbsentError);
            return false;
        }
        if(shoppingCart.getStatus().equals(ShoppingCart.STATUS_COMPLETED))
        {

            //all ready changed
            return true;
        }

        String requestUrl = ConfigurationManager.getProperty("payment-system","paypal.payflow.link");
        try {
            PostMethod post = new PostMethod(requestUrl);

            //setup the reference transaction
            post.addParameter("TENDER", "C");
            post.addParameter("TRXTYPE", "S");
            post.addParameter("PWD", ConfigurationManager.getProperty("payment-system","paypal.pwd"));
            post.addParameter("AMT", Double.toString(shoppingCart.getTotal()));
            post.addParameter("VENDOR",ConfigurationManager.getProperty("payment-system","paypal.vendor"));
            post.addParameter("PARTNER",ConfigurationManager.getProperty("payment-system","paypal.partner"));
            post.addParameter("USER", ConfigurationManager.getProperty("payment-system","paypal.user"));
            post.addParameter("ORIGID", shoppingCart.getTransactionId());

            //TODO:add currency from shopping cart
            post.addParameter("CURRENCY", shoppingCart.getCurrency());
	    log.debug("paypal sale transaction url " + post);
            switch (new HttpClient().executeMethod(post)) {
                case 200:
                case 201:
                case 202:
                    String string = post.getResponseBodyAsString();
                    String[] results = string.split("&");
                    for(String temp:results)
                    {
                        String[] result = temp.split("=");
                        //TODO: ignore the error from paypal server, add the error check after figure out the correct way to process the credit card info
                        if(result[0].contains("RESULT")&&result[1].equals("0"))
                        {
                            //successfull
                            shoppingCart.setStatus(ShoppingCart.STATUS_COMPLETED);
                            Date date= new Date();
                            shoppingCart.setPaymentDate(date);
                            for(String s:results)
                            {
                                String[] strings = s.split("=");
                                if(strings[0].contains("PNREF"))
                                {
                                    shoppingCart.setTransactionId(strings[1]);
                                    break;
                                }
                            }

                            shoppingCart.update();
                            sendPaymentApprovedEmail(c, wfi, shoppingCart);
                            return true;
                        }

                    }
                    break;
                default:
                    String result = "Paypal Reference Transaction Failure: "
                            + post.getStatusCode() +  ": " + post.getResponseBodyAsString();
                    log.error(result);
                    sendPaymentRejectedEmail(c, wfi, shoppingCart);
                    return false;
            }

            post.releaseConnection();
        }
        catch (Exception e) {
            log.error("error when submit paypal reference transaction: "+e.getMessage(), e);
            sendPaymentErrorEmail(c, wfi, null, "exception when submit reference transaction: " + e.getMessage());
            return false;
        }
        sendPaymentErrorEmail(c, wfi, shoppingCart, "chargeCard failed");
        return false;
    }

    public void generatePaypalForm(Division maindiv,ShoppingCart shoppingCart,String actionURL,String type,Context context) throws WingException,SQLException {

        //return false if there is error in loading from paypal
        String secureTokenId = getSecureTokenId();
        String secureToken = generateSecureToken(shoppingCart,secureTokenId,Integer.toString(shoppingCart.getItem()),type);
        WorkspaceItem workspaceItem=null;
        if(secureToken==null){
            EPerson ePerson = context.getCurrentUser();
            try{
                workspaceItem=WorkspaceItem.findByItemId(context,shoppingCart.getItem());
            }
            catch (Exception e)
            {
                log.error("couldn't find the item in the workspace, so block peopele other than admmin"+e);
            }
            if(workspaceItem!=null||AuthorizeManager.isAdmin(context,ePerson))
            {
                showSkipPaymentButton(maindiv,"Unfortunately, Dryad has encountered a problem communicating with our payment processor. Please continue, and we will contact you regarding payment. Error code: Secure-null");

            }
            else
            {
                //don't show the skip button if item is not in workspace steps and not admin users
                showHelpPaymentButton(maindiv,"Unfortunately, Dryad has encountered a problem communicating with our payment processor. Please contact administrator regarding payment. Error code: Secure-null");

            }
            log.error("PayPal Secure Token is null");

        }
        else{
            shoppingCart.setSecureToken(secureToken);
            shoppingCart.update();
            List list= maindiv.addDivision("paypal-iframe").addList("paypal-fields");
            list.addItem("secureTokenId","").addContent(secureTokenId);
            list.addItem("secureToken","").addContent(secureToken);
            list.addItem("testMode","").addContent(ConfigurationManager.getProperty("payment-system","paypal.mode"));
            list.addItem("link","").addContent(ConfigurationManager.getProperty("payment-system","paypal.link"));
        }
    }

    public void generateVoucherForm(Division form,String voucherCode,String actionURL,String knotId) throws WingException{

        List list=form.addList("voucher-list");
        list.addLabel("Voucher Code");
        list.addItem().addText("voucher").setValue(voucherCode);
        list.addItem().addButton("submit-voucher").setValue("Apply");

    }

    public void generateNoCostForm( Division actionsDiv,ShoppingCart shoppingCart, org.dspace.content.Item item,PaymentSystemConfigurationManager manager,PaymentSystemService paymentSystemService) throws WingException, SQLException {
        //Lastly add the finalize submission button

        Division finDiv = actionsDiv.addDivision("finalizedivision");

        if(shoppingCart.getStatus().equals(ShoppingCart.STATUS_VERIFIED))
        {
            finDiv.addPara("data-label", "bold").addContent("Your payment information has been verified.");
        }
        if(shoppingCart.getStatus().equals(ShoppingCart.STATUS_COMPLETED))
        {
            finDiv.addPara("data-label", "bold").addContent("You have already paid for this submission.");
        }
        else if(shoppingCart.getTotal()==0)
        {
           finDiv.addPara("data-label", "bold").addContent("Your total due is 0.00.");
        }
        else if(!shoppingCart.getCurrency().equals("USD"))
        {
            finDiv.addPara("data-label", "bold").addContent("Dryad's payment processing system currently only supports transactions in US dollars. We expect to enable transactions in other currencies within a few days. If you wish to complete your transaction in US dollars, please change the currency setting above. Otherwise, please complete your submission without entering payment information. We will contact you for payment details before your data is published.");
        }
        else
        {
            finDiv.addPara("data-label", "bold").addContent("You are not being charged");
        }


        finDiv.addHidden("show_button").setValue("Finalize and submit data for curation");
    }

    public void showSkipPaymentButton(Division mainDiv,String message)throws WingException{
        Division error = mainDiv.addDivision("error");
        error.addPara(message);
        error.addHidden("show_button").setValue("Skip payment and submit");
    }

    public void showHelpPaymentButton(Division mainDiv,String message)throws WingException{
        Division error = mainDiv.addDivision("error");
        error.addPara(message);
        //error.addHidden("show_button").setValue("Skip payment and submit");
    }

    public void addButtons(Division mainDiv)throws WingException{
        List buttons = mainDiv.addList("paypal-form-buttons");
        Button skipButton = buttons.addItem().addButton("skip_payment");
        skipButton.setValue("Submit");
        Button cancleButton = buttons.addItem().addButton(AbstractProcessingStep.CANCEL_BUTTON);
        cancleButton.setValue("Cancel");

    }

    //this methord should genearte a secure token from paypal and then generate a user crsedit card form
    public void generateUserForm(Context context,Division mainDiv,String actionURL,String knotId,String type,Request request, Item item, DSpaceObject dso) throws WingException, SQLException{
        PaymentSystemConfigurationManager manager = new PaymentSystemConfigurationManager();
        PaymentSystemService payementSystemService = new DSpace().getSingletonService(PaymentSystemService.class);
        PaypalService paypalService = new DSpace().getSingletonService(PaypalService.class);
        //mainDiv.setHead("Checkout");
        String errorMessage = request.getParameter("encountError");
        try{
            //create new transaction or update transaction id with item
            String previous_email = request.getParameter("login_email");
            EPerson eperson = EPerson.findByEmail(context,previous_email);
            ShoppingCart shoppingCart = payementSystemService.getShoppingCartByItemId(context,item.getID());
            if(shoppingCart.getStatus().equals(ShoppingCart.STATUS_COMPLETED))
            {
                  //shopping cart already paid, not need to generate a form
                paypalService.generateNoCostForm(mainDiv, shoppingCart,item, manager, payementSystemService);
            }
            else{

            VoucherValidationService voucherValidationService = new DSpace().getSingletonService(VoucherValidationService.class);
            String voucherCode = "";
            if(request.getParameter("submit-voucher")!=null)
            {    //user is using the voucher code
                voucherCode = request.getParameter("voucher");
                if(voucherCode!=null&&voucherCode.length()>0){
                    if(!voucherValidationService.voucherUsed(context,voucherCode)) {
                        Voucher voucher = Voucher.findByCode(context,voucherCode);
                        shoppingCart.setVoucher(voucher.getID());
                        payementSystemService.updateTotal(context,shoppingCart,null);
                    }
                    else
                    {
                        errorMessage = "The voucher code is not valid:can't find the voucher code or the voucher code has been used";
                    }
                }
                else
                {
                    shoppingCart.setVoucher(null);
                    payementSystemService.updateTotal(context,shoppingCart,null);
                }

            }

            if(shoppingCart.getTotal()==0||shoppingCart.getStatus().equals(ShoppingCart.STATUS_COMPLETED)||!shoppingCart.getCurrency().equals("USD"))
            {
                paypalService.generateNoCostForm(mainDiv, shoppingCart,item, manager, payementSystemService);
            }
            else
            {

                Division voucher = mainDiv.addDivision("voucher");
                if(errorMessage!=null&&errorMessage.length()>0) {
                    voucher.addPara("voucher-error","voucher-error").addHighlight("bold").addContent(errorMessage);

                }

                Voucher voucher1 = Voucher.findById(context,shoppingCart.getVoucher());
                if(voucher1!=null){
                    paypalService.generateVoucherForm(voucher,voucher1.getCode(),actionURL,knotId);
                }
                else if(voucherCode!=null&&voucherCode.length()>0){
                    paypalService.generateVoucherForm(voucher,voucherCode,actionURL,knotId);
                }
                else{
                    paypalService.generateVoucherForm(voucher,null,actionURL,knotId);
                }
                Division creditcard = mainDiv.addDivision("creditcard");
                paypalService.generatePaypalForm(creditcard,shoppingCart,actionURL,type,context);

            }

            }
        }catch (Exception e)
        {
            //TODO: handle the exceptions
            paypalService.showSkipPaymentButton(mainDiv,"errors in generate the payment form:"+e.getMessage());
            log.error("Exception when entering the checkout step:", e);
        }


        mainDiv.addHidden("submission-continue").setValue(knotId);
        mainDiv.addPara().addContent("NOTE : Proceed only if your submission is finalized. After submitting, a Dryad curator will review your submission. After this review, your data will be archived in Dryad, and your payment will be processed.");
        paypalService.addButtons(mainDiv);

    }

    private void sendPaymentApprovedEmail(Context c, WorkflowItem wfi, ShoppingCart shoppingCart) {

        try {

            Email email = ConfigurationManager.getEmail(I18nUtil.getEmailFilename(c.getCurrentLocale(), "payment_approved"));
            email.addRecipient(wfi.getSubmitter().getEmail());
            email.addRecipient(ConfigurationManager.getProperty("payment-system", "dryad.paymentsystem.alert.recipient"));

            email.addArgument(
                    wfi.getItem().getName()
            );

            email.addArgument(
                    wfi.getSubmitter().getFullName() + " ("  +
                            wfi.getSubmitter().getEmail() + ")");

            if(shoppingCart != null)
            {
                /** add details of shopping cart */
                PaymentSystemService paymentSystemService = new DSpace().getSingletonService(PaymentSystemService.class);
                email.addArgument(paymentSystemService.printShoppingCart(c, shoppingCart));
            }

            email.send();

        } catch (Exception e) {
            log.error(LogManager.getHeader(c, "Error sending payment approved submission email", "WorkflowItemId: " + wfi.getID()), e);
        }

    }

    private void sendPaymentWaivedEmail(Context c, WorkflowItem wfi, ShoppingCart shoppingCart) {

        try {

            Email email = ConfigurationManager.getEmail(I18nUtil.getEmailFilename(c.getCurrentLocale(), "payment_waived"));
            email.addRecipient(wfi.getSubmitter().getEmail());
            email.addRecipient(ConfigurationManager.getProperty("payment-system", "dryad.paymentsystem.alert.recipient"));

            email.addArgument(
                    wfi.getItem().getName()
            );

            email.addArgument(
                    wfi.getSubmitter().getFullName() + " ("  +
                            wfi.getSubmitter().getEmail() + ")");
            if(shoppingCart != null)
            {
                /** add details of shopping cart */
                PaymentSystemService paymentSystemService = new DSpace().getSingletonService(PaymentSystemService.class);
                email.addArgument(paymentSystemService.printShoppingCart(c, shoppingCart));
            }

            email.send();

        } catch (Exception e) {
            log.error(LogManager.getHeader(c, "Error sending payment approved submission email", "WorkflowItemId: " + wfi.getID()), e);
        }

    }

    private void sendPaymentErrorEmail(Context c, WorkflowItem wfi, ShoppingCart shoppingCart, String error) {

        try {

            Email email = ConfigurationManager.getEmail(I18nUtil.getEmailFilename(c.getCurrentLocale(), "payment_error"));
            // only send result of shopping cart errors to administrators
            email.addRecipient(ConfigurationManager.getProperty("payment-system", "dryad.paymentsystem.alert.recipient"));

            email.addArgument(
                    wfi.getItem().getName()
            );

            email.addArgument(
                    wfi.getSubmitter().getFullName() + " ("  +
                            wfi.getSubmitter().getEmail() + ")");

            email.addArgument(error);

            if(shoppingCart != null)
            {
                /** add details of shopping cart */
                PaymentSystemService paymentSystemService = new DSpace().getSingletonService(PaymentSystemService.class);
                email.addArgument(paymentSystemService.printShoppingCart(c, shoppingCart));
            }

            email.send();

        } catch (Exception e) {
            log.error(LogManager.getHeader(c, "Error sending payment rejected submission email", "WorkflowItemId: " + wfi.getID()), e);
        }

    }

    private void sendPaymentRejectedEmail(Context c, WorkflowItem wfi, ShoppingCart shoppingCart) {

        try {

            Email email = ConfigurationManager.getEmail(I18nUtil.getEmailFilename(c.getCurrentLocale(), "payment_rejected"));
            // temporarily only send result of shopping cart errors to administrators
            email.addRecipient(wfi.getSubmitter().getEmail());
            email.addRecipient(ConfigurationManager.getProperty("payment-system", "dryad.paymentsystem.alert.recipient"));

            email.addArgument(
                    wfi.getItem().getName()
            );

            email.addArgument(
                    wfi.getSubmitter().getFullName() + " ("  +
                            wfi.getSubmitter().getEmail() + ")");

            if(shoppingCart != null)
            {
                /** add details of shopping cart */
                PaymentSystemService paymentSystemService = new DSpace().getSingletonService(PaymentSystemService.class);
                email.addArgument(paymentSystemService.printShoppingCart(c, shoppingCart));
            }

            email.send();

        } catch (Exception e) {
            log.error(LogManager.getHeader(c, "Error sending payment rejected submission email", "WorkflowItemId: " + wfi.getID()), e);
        }

    }
}
