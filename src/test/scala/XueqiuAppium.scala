import java.io.ByteArrayInputStream
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.xml.parsers.{DocumentBuilder, DocumentBuilderFactory}
import javax.xml.xpath.{XPath, XPathFactory, _}

import io.appium.java_client.AppiumDriver
import io.appium.java_client.android.AndroidDriver
import io.appium.java_client.ios.IOSDriver
import io.appium.java_client.remote.MobileCapabilityType
import org.apache.commons.io.FileUtils
import org.openqa.selenium.remote.DesiredCapabilities
import org.openqa.selenium.{TakesScreenshot, OutputType, WebElement}
import org.w3c.dom.{Attr, Document, NodeList}

import scala.collection.mutable.{ListBuffer, Map}
import scala.reflect.io.File
import scala.util.control.Breaks._
import scala.util.{Failure, Success, Try}


case class ELement(url: String, tag: String, id: String, name: String) {
  override def toString(): String = {
    if (tag.toLowerCase().contains("edit")) {
      s"${url},${tag}_${id},"
    } else {
      s"${url},${tag}_${id},${name}"
    }
  }
}


/**
  * Created by seveniruby on 15/11/28.
  */
class XueqiuAppium {
  type AM = Map[String, String]
  implicit var driver: AppiumDriver[WebElement] = _
  val elements: scala.collection.mutable.Map[String, Boolean] = scala.collection.mutable.Map()
  //包括backButton
  val blackList = ListBuffer("stock_item_value", "[0-9]{2}", "弹幕", "发送", "保存", "确定",
    "up", "user_profile_icon", "selectAll", "cut", "copy", "send", "买[0-9]", "卖[0-9]")
  val rule = ListBuffer[Map[String, String]]()
  var isSkip = false
  val stack = scala.collection.mutable.Stack[String]()
  val clickedList = ListBuffer[String]()
  val timestamp = new java.text.SimpleDateFormat("YYYYMMddHHmm").format(new java.util.Date())
  var md5Last = ""
  var nId = 0
  var automationName = "appium"
  var platformName = ""
  var backButton = ""
  var depth=0
  /**优先遍历元素*/
  val firstList=ListBuffer[String]()
  var pageSource=""

  case class Node[T](value: T, children: ListBuffer[Node[T]] = ListBuffer[Node[T]]()) {
    val nId: String = {
      getNodeId()
    }

    def equals(node: Node[AM]): Boolean = {
      List("url", "id", "name").foreach(attr => {
        if (node.value(attr) != value.asInstanceOf[AM](attr)) {
          return false
        }
      })
      return true
    }
  }

  val freemind = Node(Map("url" -> "Start", "id" -> "Start", "name" -> null))

  def generateFreeMind(list: ListBuffer[ELement]): Unit = {
    // 保留上一个node用来加linktarget箭头
    var lastAddedNodes = ListBuffer[Node[AM]]()
    list.foreach(l => {
      var fixedUrl = l.url
      // 去掉url的前缀: android/gz  com.xueqiu.android/gz
      if (l.url.split("/").length > 1) {
        fixedUrl = l.url.split("/")(1)
      }
      var nameNode = Node(Map("url" -> fixedUrl,
        "id" -> l.id,
        "name" -> l.name))
      lastAddedNodes = appendNodes(freemind, nameNode, lastAddedNodes)
    })

    println(freemind)

    println( """<map version="1.0.1">""")
    toXml(freemind)
    println("</map>")

  }

  def getNodeId(): String = {
    nId += 1
    return nId.toString
  }

  def getArrowId(): String = {
    nId += 1
    return nId.toString
  }

  def appendNodes(currenTree: Node[AM], node: Node[AM], lastAddedNodes: ListBuffer[Node[AM]]): ListBuffer[Node[AM]] = {
    var newTree = currenTree
    var addedNodes = ListBuffer[Node[AM]]()

    //add url node
    if (node.value("url") != null) {
      val newNode = Node(Map("url" -> node.value("url"),
        "id" -> null,
        "name" -> null))
      newTree = appendNode(newTree, newNode)
      addedNodes += newTree
    }
    //add id node
    if (node.value("id") != null) {
      val newNode = Node(Map("url" -> node.value("url"),
        "id" -> node.value("id"),
        "name" -> null))
      newTree = appendNode(newTree, newNode)
      addedNodes += newTree
    }
    //add name node
    if (node.value("name") != null) {
      val newNode = Node(Map("url" -> node.value("url"),
        "id" -> node.value("id"),
        "name" -> node.value("name")))
      newTree = appendNode(newTree, newNode)
      addedNodes += newTree
    }
    //add targetlink to just append node
    if (lastAddedNodes.length > 0 && addedNodes.length > 0 && lastAddedNodes.last.value("url") != addedNodes.head.value("url")) {
      var arrowId = getArrowId()
      //add attrs of linktarget to new node
      addedNodes.head.value += ("type" -> "linktarget")
      addedNodes.head.value += ("destination" -> addedNodes.head.nId)
      addedNodes.head.value += ("source" -> lastAddedNodes.last.nId)
      addedNodes.head.value += ("aid" -> s"Arrow_ID_${arrowId}")

      //add attrs of arrowlink to the last node
      lastAddedNodes.last.value += ("type" -> "arrowlink")
      lastAddedNodes.last.value += ("destination" -> addedNodes.head.nId)
      lastAddedNodes.last.value += ("aid" -> s"Arrow_ID_${arrowId}")
    }

    return addedNodes
  }

  def appendNode(currenTree: Node[AM], node: Node[AM]): Node[AM] = {
    find(currenTree, node) match {
      case Some(v) => {
        return v
      }
      case None => {
        currenTree.children.append(node)
        return node
      }
    }
  }


  def toXml(tree: Node[AM]): Unit = {
    val before = (tree: Node[AM]) => {
      var output = ""
      if (tree.value("name") != null) {
        output = tree.value("name")
      } else if (tree.value("id") != null) {
        output = tree.value("id")
      } else if (tree.value("url") != null) {
        output = tree.value("url")
      }
      println( s"""<node ID="ID_${tree.nId}" TEXT="${output}">""")

      //add linktarget and arrowlink if needed
      if (tree.value.contains("type")) {
        tree.value("type") match {
          case "linktarget" => {
            println( s"""<linktarget COLOR="#b0b0b0" DESTINATION="ID_${tree.value("destination")}" ENDARROW="Default" ENDINCLINATION="24;0;" ID="${tree.value("aid")}" SOURCE="${tree.value("source")}" STARTARROW="None" STARTINCLINATION="24;0;"/>""")
          }
          case "arrowlink" => {
            println( s"""<arrowlink DESTINATION="ID_${tree.value("destination")}" ENDARROW="Default" ENDINCLINATION="24;0;" ID="${tree.value("aid")}" STARTARROW="None" STARTINCLINATION="24;0;"/>""")
          }
        }
      }
    }
    val after = (tree: Node[AM]) => {
      println("</node>")
    }
    traversal[AM](tree, before, after)
  }

  def traversal[T](tree: Node[T],
                   before: (Node[T]) => Unit = (x: Node[T]) => Unit,
                   after: (Node[T]) => Unit = (x: Node[T]) => Unit): Unit = {
    before(tree)
    tree.children.foreach(t => {
      traversal(t, before, after)
    })
    after(tree)
  }

  def find(tree: Node[AM], node: Node[AM]): Option[Node[AM]] = {
    if (tree.equals(node)) {
      return Some(tree)
    }
    tree.children.map(t => {
      find(t, node) match {
        case Some(v) => return Some(v)
        case None => {}
      }
    })
    return None
  }


  def setupIOS(app: String, url: String = "http://127.0.0.1:4723/wd/hub") {
    platformName = "iOS"
    val capabilities = new DesiredCapabilities()
    capabilities.setCapability("deviceName", "iPhone 4s")
    capabilities.setCapability("platformName", "iOS")
    capabilities.setCapability("platformVersion", "9.1")
    capabilities.setCapability("autoLaunch", "true")
    capabilities.setCapability("autoAcceptAlerts", "true")
    //主要做遍历测试和异常测试. 所以暂不使用selendroid. 兼容性测试需要使用selendroid
    //capabilities.setCapability("automationName", "Selendroid")
    //todo: Appium模式太慢
    capabilities.setCapability("automationName", "Appium")

    capabilities.setCapability(MobileCapabilityType.APP, app)
    //capabilities.setCapability(MobileCapabilityType.APP, "http://xqfile.imedao.com/android-release/xueqiu_681_10151900.apk")
    //driver = new XueqiuDriver[WebElement](new URL("http://127.0.0.1:4729/wd/hub"), capabilities)
    driver = new IOSDriver[WebElement](new URL(url), capabilities)


    //driver.manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS)
    //PageFactory.initElements(new AppiumFieldDecorator(driver, 10, TimeUnit.SECONDS), this)
    //implicitlyWait(Span(10, Seconds))
  }


  def setupAndroid(app: String, url: String = "http://127.0.0.1:4723/wd/hub") {
    platformName = "Android"
    val capabilities = new DesiredCapabilities()
    capabilities.setCapability("deviceName", "emulator-5554");
    capabilities.setCapability("platformVersion", "4.4");
    capabilities.setCapability("appPackage", "com.xueqiu.android");
    capabilities.setCapability(MobileCapabilityType.APP_ACTIVITY, "com.xueqiu.android.view.WelcomeActivityAlias")
    //capabilities.setCapability("appActivity", ".ApiDemos");
    capabilities.setCapability("autoLaunch", "true")
    capabilities.setCapability("unicodeKeyboard", "true")
    //主要做遍历测试和异常测试. 所以暂不使用selendroid. 兼容性测试需要使用selendroid
    //capabilities.setCapability("automationName", "Selendroid")
    //todo: Appium模式太慢
    capabilities.setCapability("automationName", "Appium")

    capabilities.setCapability(MobileCapabilityType.APP, app)
    //capabilities.setCapability(MobileCapabilityType.APP, "http://xqfile.imedao.com/android-release/xueqiu_681_10151900.apk")
    //driver = new XueqiuDriver[WebElement](new URL("http://127.0.0.1:4729/wd/hub"), capabilities)
    driver = new AndroidDriver[WebElement](new URL(url), capabilities)


    //driver.manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS)
    //PageFactory.initElements(new AppiumFieldDecorator(driver, 10, TimeUnit.SECONDS), this)
    //implicitlyWait(Span(10, Seconds))
  }

  def black(key: String): Unit = {
    blackList.append(key)
  }

  def md5(format: String) = {
    //import sys.process._
    //s"echo ${format}" #| "md5" !

    //new java.lang.String(MessageDigest.getInstance("MD5").digest(format.getBytes("UTF-8")))
    java.security.MessageDigest.getInstance("MD5").digest(format.getBytes("UTF-8")).map(0xFF & _).map {
      "%02x".format(_)
    }.foldLeft("") {
      _ + _
    }
  }


  def rule(loc: String, action: String): Unit = {
    rule.append(Map(loc -> action))
  }


  /**
    * 根据xpath来获得想要的元素列表
    * @param raw
    * @param xpath
    * @return
    */
  def getAllElements(raw: String, xpath: String): ListBuffer[Map[String, String]] = {
    val nodeList = ListBuffer[Map[String, String]]()
    val builderFactory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()
    val builder: DocumentBuilder = builderFactory.newDocumentBuilder()
    val xPath: XPath = XPathFactory.newInstance().newXPath()
    val compexp = xPath.compile(xpath)
    val document: Document = builder.parse(new ByteArrayInputStream(raw.replaceAll("[\\x00-\\x1F]", "").getBytes(StandardCharsets.UTF_8)))
    val node = compexp.evaluate(document, XPathConstants.NODESET)
    node match {
      case n: NodeList => {
        println(s"xpath=${xpath} length=${n.getLength}")
        0 until n.getLength foreach (i => {
          val nodeMap = Map[String, String]()
          nodeMap("tag") = n.item(i).getNodeName
          val nodeAttributes = n.item(i).getAttributes
          0 until nodeAttributes.getLength foreach (a => {
            val attr = nodeAttributes.item(a).asInstanceOf[Attr]
            nodeMap(attr.getName) = attr.getValue
          })
          if(!nodeMap.contains("name")){
            nodeMap("name")=""
            nodeMap("value")=""
          }
          if(nodeMap.contains("resource-id")){
            nodeMap("name")=nodeMap("resource-id").split('/').last
          }
          if(nodeMap.contains("text")){
            nodeMap("value")=nodeMap("text")
          }

          println(nodeMap)
          nodeList.append(nodeMap)
        })
      }
      case _ => println("typecast to NodeList failed")
    }
    return nodeList

  }

  /**
    * 尝试识别当前的页面
    * @return
    */
  def getSchema(): String ={
    return ""
  }

  def getUrl(): String = {
    return "not impled"
  }

  /**
    * 获取控件的基本属性并设置一个唯一的uid作为识别. screenName+id+name
    * @param x
    * @return
    */
  def getElementId(x: Map[String, String]): Option[ELement] = {
    val tag = x.getOrElse("tag", "NoTag")

    //name为Android的description/text属性, 或者iOS的value属性
    var name = x.getOrElse("value", "").replace("\n", "\\n")
    //name为id/name属性. 为空的时候为value属性

    //id表示android的resource-id或者iOS的name属性
    val resourceId = x.getOrElse("name", "")
    //id为value/text属性
    //todo: 将来做调整, 这个跟findElmentByUid有关系
    val id = resourceId.split('/').last

    //所在的页面. 通过控制url可以决定一个元素的唯一性
    val url = getUrl()
    val node = ELement(url, tag, id, name)
    return Some(node)

    //    if (List("android", "com.xueqiu.android").contains(appName)) {
    //      return Some(node)
    //    } else {
    //      return None
    //    }

  }

  def isReturn(): Boolean = {
    return false
  }

  /**
    * 黑名单过滤. 通过正则匹配
    * @param uid
    * @return
    */
  def isBlack(uid: ELement): Boolean = {
    blackList.filter(b => {
      uid.id.matches(s".*${b}.*") || uid.name.matches(s".*${b}.*")
    }).length > 0
  }

  def getClickableElements(): Option[Seq[Map[String, String]]] = {
    println("getClickableElements start")
    return None
  }

  def first(xpath:String): Unit ={
    firstList.append(xpath)
  }
  def back(name: String): Unit = {
    backButton = name
    black(backButton)
  }

  def refreshPage(): Unit ={
    doAppium(driver.getPageSource) match {
      case Some(v)=> pageSource=v
      case None => {println("get page source error")}
    }
    println(pageSource)
    val contexts=doAppium(driver.getContextHandles).getOrElse("")
    val windows=doAppium(driver.getWindowHandles).getOrElse("")
    println(s"context=${contexts} windows=${windows}")
    println("schema="+getSchema())
  }

  def traversal(): Unit = {
    println("traversal start")
    depth+=1
    println(s"depth=${depth}")
    println("refresh page")
    refreshPage()
    var needBack = true
    var needSkip = false
    needBack = !isReturn()
    //先判断是否命中规则.
    doRuleAction()
    //在selendroid的JsonXmlUtil.java:70有个bug. 导致无法获取当前界面的所有元素. ^_^
    val all = getClickableElements().getOrElse(Seq[Map[String, String]]())
    if (all.length == 0) {
      //获取列表失败就重试
      println("activity change")
      needBack = false
    } else {
      //获得所有的可点击元素
      breakable {
        var index = 0
        all.foreach(x => {
          index += 1
          println(s"index=${index}")
          //是否需要退出
          if (isReturn()) {
            needBack = true
            println("break")
            break()
          } else {
            println("no need to break")
          }
          //如果触发了任意操作, 当前界面会变化. 需要重新刷新, 跳过无谓的循环
          val uid = getElementId(x) match {
            case Some(v) => v
            case None => {
              //遍历的元素都是有id, 如果出现了没有NoId或者NoText, 表明是获取元素属性的方法失败了. 发生了异常.
              //获取id异常表示元素出了问题. 说明界面刷新过, 需要重新刷新, 但是不需要后退
              println("exception")
              needBack = false
              break()
            }
          }
          println(s"id=${uid}")

          //是否黑名单
          needSkip = isBlack(uid)
          if(needSkip==true){
            println("in black list")
          }else{
            println("not in black list")
          }
          //是否已经点击过
          //todo: 新界面入口需要设置为false
          if(needSkip==false) {
            if (elements.contains(uid.toString())) {
              println("skip")
              needSkip = true
            } else {
              needSkip = false
              println("first show, need click")
            }
          }
          //如果未曾点击
          if (needSkip == false) {
            println("just click")
            elements(uid.toString()) = true
            //doDefaultAction(uid)
            doAppiumAction(uid, "click") match {
              case Some(v) => {
                println("do appium action success")
              }
              case None => {
                println("do appium action exception, break")
              }
            }
            //说明还不需要back到上一界面, 遍历完所有的元素才表示需要回退
            needBack = false
            //任何点击都需要重新刷新元素. 防止其他元素被遮盖

            //任何界面变化都需要进入新的递归. 而不是只到新界面.
            traversal()

          } else {
            println("already clicked, so skip")
          }
        })
      }
    }
    //子界面遍历返回后继续遍历当前界面中剩下的界面
    if (needBack == true) {
      println("back")
      if (backButton == "") {
        //todo: iOS上的back貌似有问题
        driver.navigate().back()
      } else {
        doAppiumActionByName(backButton, "click")
      }
      //任何界面变化都需要进入新的递归. 而不是只到新界面.
      traversal()

    }
    depth-=1
  }

  //todo:优化查找方法
  def findElementByUid(uid: ELement): Option[WebElement] = {
    if(uid.id !="") {
      println(s"find by id")
      doAppium(driver.findElementById(uid.id)) match {
        case Some(v) => return Some(v)
        case None => {}
      }
    }
    if(uid.name!=""){
      println(s"find by name")
      doAppium(driver.findElementByName(uid.name)) match {
        case Some(v) => return Some(v)
        case None => {}
      }
    }
    println(s"find by xpath")
    doAppium(driver.findElementByXPath(s"//*[@value='${uid.name}']")) match {
      case Some(v) => return Some(v)
      case None => {}
    }

    return None
  }

  def doAppium[T](r: => T): Option[T] = {
    Try(r) match {
      case Success(v) => {
        return Some(v)
      }
      case Failure(e) => {
        println("message=" + e.getMessage)
        println("cause=" + e.getCause)
        //println(e.getStackTrace.mkString("\n"))
        return None
      }
    }

  }

  def doAppiumActionByName(name: String, action: String = "click"): Option[Unit] = {
    val element = ELement("", "*", "", name)
    doAppiumAction(element, action)
  }

  def saveLog(): Unit ={
    //记录点击log
    File(s"clickedList_${timestamp}.log").writeAll(clickedList.mkString("\n"))
    File(s"ElementList_${timestamp}.log").writeAll(elements.mkString("\n"))
  }

  def saveScreen(path:String): Unit ={
    doAppium((driver.asInstanceOf[TakesScreenshot]).getScreenshotAs(OutputType.FILE)) match {
      case Some(src)=>{
        FileUtils.copyFile(src, new java.io.File(path))
      }
      case None=>{
        println("get screenshot error")
      }
    }
  }

  def doAppiumAction(e: ELement, action: String = "click"): Option[Unit] = {
    findElementByUid(e) match {
      case Some(v) => {
        action match {
          case "click" => {
            println(s"click ${v}")
            val res = doAppium(v.click())
            clickedList.append(e.toString())
            saveLog()
            saveScreen(s"pic/${timestamp}_${depth}_"+e.toString().replace(",","_").replace(" ", "")+".jpg")
            doAppium(driver.hideKeyboard())
            return res
          }
          case str: String => {
            println(s"sendkeys ${v} with ${str}")
            doAppium(v.sendKeys(str)) match {
              case Some(v) => {
                clickedList.append(e.toString())
                saveLog()
                doAppium(driver.hideKeyboard())
                return Some(v)
              }
              case None => return None
            }
          }
        }

      }
      case None => {
        println("find error")
        return None
      }
    }


  }

  /**
    * 子类重载
    * @return
    */
  def getRuleMatchNodes(): ListBuffer[Map[String, String]] ={
    return ListBuffer[Map[String, String]]()
  }

  //通过规则实现操作. 不管元素是否被点击过
  def doRuleAction(): Unit = {
    println("rule match start")
    //先判断是否在期望的界面里. 提升速度
    rule.foreach(r => {
      println("for each rule")
      val idOrName = r.head._1.split('.').last
      val action = r.head._2
      println(s"idOrName=${idOrName} action=${action}")
      //重新获取变化后的列表
      val all = getRuleMatchNodes()
      breakable{
        (all.filter(_ ("name").matches(idOrName)) ++ all.filter(_ ("value").matches(idOrName))).distinct.foreach(x => {
        println("hit rule action")
        println(x)
        //获得正式的定位id
        getElementId(x) match {
          case Some(e) => {
            println(s"element=${e} action=${action}")
            println("do rule action")
            doAppiumAction(e, action) match {
              case None=>{
                println("do rule action fail")
                break()
              }
              case Some(v)=>{
                println("do rule action success")
              }
            }
            //todo: 暂不删除, 允许复用
            //rule -= r
          }
          case None => println("get element id error")
        }
      })
      }
    })

  }

}