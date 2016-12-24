package providers

import com.feth.play.module.mail.Mailer.MailerFactory
import com.feth.play.module.pa.PlayAuthenticate
import com.feth.play.module.pa.providers.password._
import controllers.routes
import constants._
import generated.Tables
import play.Logger
import play.data.Form
import play.i18n.Lang
import play.inject.ApplicationLifecycle
import play.mvc.{Call, Http}
import com.feth.play.module.mail.Mailer.Mail.Body

import scala.collection.JavaConversions
import services._
import views.form._
import javax.inject.Inject
import javax.inject.Singleton
import java.lang.reflect.Method
import java.util
import java.util.UUID

import com.feth.play.module.pa.providers.password.UsernamePasswordAuthProvider.{LoginResult, SignupResult}
import generated.Tables.UserRow
import play.api.i18n.MessagesApi

@Singleton
class AuthProvider @Inject() (val messagesApi: MessagesApi,
                              val userService: UserService,
                              val tokenActionService: TokenActionService,
                              auth: PlayAuthenticate,
                              lifecycle: ApplicationLifecycle,
                              mailerFactory: MailerFactory)
  extends UsernamePasswordAuthProvider[String, SecuredUserLoginAuth, SecuredUserSignupAuth, Login, Signup] (auth, lifecycle, mailerFactory) {
  //-------------------------------------------------------------------
  // public
  //-------------------------------------------------------------------
  def getLoginForm: Form[Login] = {
    null
  }

  //-------------------------------------------------------------------
  def getSignupForm: Form[Signup] = {
    null
  }

  //-------------------------------------------------------------------
  def sendPasswordResetMailing (user: UserRow, ctx: Http.Context) {
    val token = generatePasswordResetRecord (user)
    val subject = getPasswordResetMailingSubject (user, ctx)
    val body = getPasswordResetMailingBody(token, user, ctx)
    sendMail(subject, body, getEmailName(user))
  }

  //-------------------------------------------------------------------
  def isLoginAfterPasswordReset: Boolean = {
    getConfiguration.getBoolean (SETTING_KEY_LINK_LOGIN_AFTER_PASSWORD_RESET)
  }

  //-------------------------------------------------------------------
  def sendVerifyEmailMailingAfterSignup(user: UserRow, ctx: Http.Context) {
    val subject = getVerifyEmailMailingSubjectAfterSignup (user, ctx)
    val token = generateVerificationRecord (user)
    val body = getVerifyEmailMailingBodyAfterSignup(token, user, ctx)
    sendMail(subject, body, getEmailName (user))
  }

  //-------------------------------------------------------------------
  // protected
  //-------------------------------------------------------------------
  override protected def neededSettingKeys : java.util.List[String] = {
    val needed = new util.ArrayList[String](super.neededSettingKeys)
    needed.add(SETTING_KEY_VERIFICATION_LINK_SECURE)
    needed.add(SETTING_KEY_PASSWORD_RESET_LINK_SECURE)
    needed.add(SETTING_KEY_LINK_LOGIN_AFTER_PASSWORD_RESET)
    needed
  }

  //-------------------------------------------------------------------
  protected def signupUser(signupAuthUser: SecuredUserSignupAuth): UsernamePasswordAuthProvider.SignupResult = {
    val user: Tables#UserRow = userService.findByAuthUser (signupAuthUser).get
    if (user != null) {
      if (user.emailValidated) {
        // This user exists, has its email validated and is active
        SignupResult.USER_EXISTS
      } else {
        // this user exists, is active but has not yet validated its
        // email
        SignupResult.USER_EXISTS_UNVERIFIED
      }
    }

    // The user either does not exist or is inactive - create a new one
    val newUser = userService.create(signupAuthUser)

    // Usually the email should be verified before allowing login, however
    // if you return
    // return SignupResult.USER_CREATED;
    // then the user gets logged in directly
    SignupResult.USER_CREATED_UNVERIFIED
  }

  //-------------------------------------------------------------------
  protected def loginUser (authUser: SecuredUserLoginAuth): UsernamePasswordAuthProvider.LoginResult = {
    val user = userService.findByAuthUser(authUser).get
    if (user == null) {
      LoginResult.NOT_FOUND
    } else {
      if (! user.emailValidated) {
        LoginResult.USER_UNVERIFIED
      } else {
        import scala.collection.JavaConversions._
        val linkedAccounts = JavaConversions.seqAsJavaList(userService.linkedAccounts(user))
        for (linkedAccount <- linkedAccounts) {
          if (getKey == linkedAccount.providerKey) {
            if (authUser.checkPassword (linkedAccount.providerPassword, authUser.getPassword) ) {
              // Password was correct
              LoginResult.USER_LOGGED_IN

            } else {
              // if you don't return here,
              // you would allow the user to have
              // multiple passwords defined
              // usually we don't want this
              LoginResult.WRONG_PASSWORD
            }
          }
        }
        LoginResult.WRONG_PASSWORD
      }
    }
  }

  //-------------------------------------------------------------------
  protected def userExists(authUser: UsernamePasswordAuthUser): Call = {
    routes.Signup.exists
  }

  //-------------------------------------------------------------------
  protected def userUnverified(authUser: UsernamePasswordAuthUser): Call = {
    routes.Signup.unverified
  }

  //-------------------------------------------------------------------
  protected def buildSignupAuthUser(signup: Signup, ctx: Http.Context): SecuredUserSignupAuth = {
    new SecuredUserSignupAuth (signup)
  }

  //-------------------------------------------------------------------
  protected def buildLoginAuthUser(login: Login, ctx: Http.Context): SecuredUserLoginAuth = {
    new SecuredUserLoginAuth (login.getPassword, login.getEmail)
  }

  //-------------------------------------------------------------------
  protected def transformAuthUser(authUser: SecuredUserSignupAuth, context: Http.Context): SecuredUserLoginAuth = {
    new SecuredUserLoginAuth (authUser.getEmail)
  }

  //-------------------------------------------------------------------
  protected def getVerifyEmailMailingSubject (user: SecuredUserSignupAuth, ctx: Http.Context): String = {
    messagesApi("playauthenticate.password.verify_signup.subject")
  }

  //-------------------------------------------------------------------
  override protected def onLoginUserNotFound(context: Http.Context): String = {
    context.flash.put (FlashKey.FLASH_ERROR_KEY, messagesApi("playauthenticate.password.login.unknown_user_or_pw") )
    super.onLoginUserNotFound (context)
  }

  //-------------------------------------------------------------------
  protected def getVerifyEmailMailingBody(token: String, user: SecuredUserSignupAuth, ctx: Http.Context): Body = {
    val isSecure: Boolean = getConfiguration.getBoolean (SETTING_KEY_VERIFICATION_LINK_SECURE)
    val url: String = routes.Signup.verify (token).absoluteURL (ctx.request, isSecure)
    val lang: Lang = Lang.preferred(ctx.request.acceptLanguages)
    val langCode: String = lang.code
    val html: String = getEmailTemplate ("views.html.account.signup.email.verify_email", langCode, url, token, user.getName, user.getEmail)
    val text: String = getEmailTemplate ("views.txt.account.signup.email.verify_email", langCode, url, token, user.getName, user.getEmail)
    new Body(text, html)
  }

  //-------------------------------------------------------------------
  protected def generateVerificationRecord(user: SecuredUserSignupAuth): String = {
    generateVerificationRecord(userService.findByAuthUser (user).get)
  }

  //-------------------------------------------------------------------
  protected def generateVerificationRecord(user: UserRow): String = {
    val token: String = generateToken
    // Do database actions, etc.
    tokenActionService.create(user, TokenActionKey.EMAIL_VERIFICATION, token)
    token
  }

  //-------------------------------------------------------------------
  protected def generatePasswordResetRecord(user: UserRow): String = {
    val token: String = generateToken
    tokenActionService.create(user, TokenActionKey.PASSWORD_RESET, token)
    token
  }

  //-------------------------------------------------------------------
  protected def getPasswordResetMailingSubject(user: Tables#UserRow, ctx: Http.Context): String = {
    messagesApi("playauthenticate.password.reset_email.subject")
  }

  //-------------------------------------------------------------------
  protected def getPasswordResetMailingBody(token: String, user: Tables#UserRow, ctx: Http.Context) : Body = {
    val isSecure: Boolean = getConfiguration.getBoolean (SETTING_KEY_PASSWORD_RESET_LINK_SECURE)
    val url: String = routes.Signup.resetPassword (token).absoluteURL (ctx.request, isSecure)
    val lang: Lang = Lang.preferred (ctx.request.acceptLanguages)
    val langCode: String = lang.code
    val html: String = getEmailTemplate ("views.html.account.email.password_reset", langCode, url, token, user.username, user.email)
    val text: String = getEmailTemplate ("views.txt.account.email.password_reset", langCode, url, token, user.username, user.email)
    new Body(text, html)
  }

  //-------------------------------------------------------------------
  protected def getVerifyEmailMailingSubjectAfterSignup (user: Tables#UserRow, ctx: Http.Context): String = {
    messagesApi("playauthenticate.password.verify_email.subject")
  }

  //-------------------------------------------------------------------
  protected def getEmailTemplate (template: String, langCode: String, url: String, token: String, name: String, email: String): String = {
    var cls: Class[_] = null
    var ret: String = null
    try {
      cls = Class.forName (template + "_" + langCode)
    }
    catch {
      case e: ClassNotFoundException => {
        Logger.warn ("Template: '" + template + "_" + langCode + "' was not found! Trying to use English fallback template instead.")
      }
    }
    if (cls == null) {
      try {
        cls = Class.forName (template + "_" + EMAIL_TEMPLATE_FALLBACK_LANGUAGE)
      }
      catch {
        case e: ClassNotFoundException => {
          Logger.error("Fallback template: '" + template + "_" + EMAIL_TEMPLATE_FALLBACK_LANGUAGE + "' was not found either!")
        }
      }
    }

    if (cls != null) {
      var htmlRender: Method = null
      try {
        htmlRender = cls.getMethod ("render", classOf[String], classOf[String], classOf[String], classOf[String] )
        ret = htmlRender.invoke (null, url, token, name, email).toString
      }
      catch {
        case exception: Throwable => {
          exception.printStackTrace ()
        }
      }
    }
    ret
  }

  //-------------------------------------------------------------------
  protected def getVerifyEmailMailingBodyAfterSignup (token: String, user: UserRow, ctx: Http.Context): Body = {
    val isSecure = getConfiguration.getBoolean(SETTING_KEY_VERIFICATION_LINK_SECURE)
    val url = routes.Signup.verify (token).absoluteURL (ctx.request, isSecure)
    val lang = Lang.preferred(ctx.request.acceptLanguages)
    val langCode = lang.code
    val html = getEmailTemplate("views.html.account.email.verify_email", langCode, url, token, user.username, user.email)
    val text = getEmailTemplate("views.txt.account.email.verify_email", langCode, url, token, user.username, user.email)
    new Body(text, html)
  }

  //-------------------------------------------------------------------
  // private
  //-------------------------------------------------------------------
  private def generateToken = UUID.randomUUID.toString

  //-------------------------------------------------------------------
  private def getEmailName(user: UserRow): String = {
    getEmailName(user.email, user.username)
  }

  //-------------------------------------------------------------------
  // members
  //-------------------------------------------------------------------
  private lazy val SETTING_KEY_VERIFICATION_LINK_SECURE: String = UsernamePasswordAuthProvider.SETTING_KEY_MAIL + "." + "verificationLink.secure"
  private lazy val SETTING_KEY_PASSWORD_RESET_LINK_SECURE: String = UsernamePasswordAuthProvider.SETTING_KEY_MAIL + "." + "passwordResetLink.secure"
  private lazy val SETTING_KEY_LINK_LOGIN_AFTER_PASSWORD_RESET: String = "loginAfterPasswordReset"
  private lazy val EMAIL_TEMPLATE_FALLBACK_LANGUAGE: String = "en"
}