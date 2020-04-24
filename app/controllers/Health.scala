package controllers

import com.iofficecorp.appinfoclient._
import javax.inject.Inject
import play.api.mvc._

class Health @Inject()(val appInfo: ApplicationInfoClient) extends Controller {
  def index = Action { request =>
    Ok("true")
  }
}
