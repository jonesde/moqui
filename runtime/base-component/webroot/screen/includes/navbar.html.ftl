
<!-- .navbar -->
<nav class="navbar navbar-inverse navbar-fixed-top"><#-- navbar-static-top -->
  <div class="container-fluid">
    <!-- Brand and toggle get grouped for better mobile display -->
    <header class="navbar-header">
        <button type="button" class="navbar-toggle" data-toggle="collapse" data-target=".navbar-ex1-collapse">
            <span class="sr-only">Toggle navigation</span>
            <span class="icon-bar"></span>
            <span class="icon-bar"></span>
            <span class="icon-bar"></span>
        </button>
        <#assign headerLogoList = sri.getThemeValues("STRT_HEADER_LOGO")>
        <#if headerLogoList?has_content><a href="/" class="navbar-brand"><img src="${headerLogoList?first}" alt="Home" height="50"></a></#if>
        <#assign headerTitleList = sri.getThemeValues("STRT_HEADER_TITLE")>
        <#if headerTitleList?has_content><div class="navbar-text">${headerTitleList?first}</div></#if>
    </header>
    <#--
    <div class="topnav">
        <div class="btn-toolbar">
    -->
            <#-- uncomment if/when we have a sidebar
            <div class="btn-group">
                <a data-placement="bottom" data-original-title="Show / Hide Sidebar" data-toggle="tooltip" class="btn btn-success btn-sm" id="changeSidebarPos">
                    <i class="fa fa-expand"></i>
                </a>
            </div>
            -->
            <#--
            <#if ec.getUser().getUserId()?has_content>
                <div class="btn-group">
                    <a data-placement="bottom" data-original-title="E-mail" data-toggle="tooltip" class="btn btn-default btn-sm">
                        <i class="fa fa-envelope"></i>
                        <span class="label label-warning">5</span>
                    </a>
                    <a data-placement="bottom" data-original-title="Messages" href="#" data-toggle="tooltip" class="btn btn-default btn-sm">
                        <i class="fa fa-comments"></i>
                        <span class="label label-danger">4</span>
                    </a>
                </div>
                <div class="btn-group">
                    <a data-placement="bottom" data-original-title="Document" href="#" data-toggle="tooltip" class="btn btn-default btn-sm">
                        <i class="fa fa-file"></i>
                    </a>
                    <a data-toggle="modal" data-original-title="Help" data-placement="bottom" class="btn btn-default btn-sm" href="#helpModal">
                        <i class="fa fa-question"></i>
                    </a>
                </div>
                <div class="btn-group">
                    <a href="/Login/logout" data-toggle="tooltip" data-original-title="Logout ${(ec.getUser().getUserAccount().userFullName)!!}" data-placement="bottom" class="btn btn-metis-1 btn-sm">
                        <i class="fa fa-power-off"></i>
                    </a>
                </div>
            </#if>
            <#--
        </div>
    </div>--><!-- /.topnav -->
    <div id="navbar-buttons" class="collapse navbar-collapse navbar-ex1-collapse">
        <!-- .nav -->
        <ul id="header-menus" class="nav navbar-nav">
            <#--
            <#assign menuTitle = sri.getActiveScreenDef().getDefaultMenuName()!"Menu">
            <#-- <#assign menuUrlInfo = sri.buildUrl("")> ${menuUrlInfo.minimalPathUrlWithParams} - ->
            <li class='dropdown'>
                <a href="#" class="dropdown-toggle" data-toggle="dropdown">${menuTitle}<b class="caret"></b></a>
                <ul class="dropdown-menu">
                <#list sri.getActiveScreenDef().getMenuSubscreensItems() as subscreensItem>
                    <#assign urlInfo = sri.buildUrl(subscreensItem.name)>
                    <#if urlInfo.isPermitted()>
                        <li class="<#if urlInfo.inCurrentScreenPath>active</#if>"><a href="<#if urlInfo.disableLink>#<#else>${urlInfo.minimalPathUrlWithParams}</#if>">${ec.l10n.localize(subscreensItem.menuTitle)}</a></li>
                    </#if>
                </#list>
                </ul>
            </li>
            -->
            <#--
            <li> <a href="dashboard.html">Dashboard</a>  </li>
            <li> <a href="table.html">Tables</a>  </li>
            <li> <a href="file.html">File Manager</a>  </li>
            <li class='dropdown active'>
                <a href="#" class="dropdown-toggle" data-toggle="dropdown">
                    Form Elements
                    <b class="caret"></b>
                </a>
                <ul class="dropdown-menu">
                    <li> <a href="form-general.html">General</a>  </li>
                    <li> <a href="form-validation.html">Validation</a>  </li>
                    <li> <a href="form-wysiwyg.html">WYSIWYG</a>  </li>
                    <li> <a href="form-wizard.html">Wizard &amp; File Upload</a>  </li>
                </ul>
            </li>
            -->
        </ul><!-- /.nav -->
        <div id="navbar-menu-crumbs"></div>
        <div class="navbar-text">${html_title!(ec.l10n.localize(sri.screenUrlInfo.targetScreen.getDefaultMenuName()!"Page"))}</div>
        <a href="/Login/logout" data-toggle="tooltip" data-original-title="Logout ${(ec.getUser().getUserAccount().userFullName)!}" data-placement="bottom" class="btn btn-danger btn-sm navbar-btn navbar-right">
            <i class="glyphicon glyphicon-off"></i>
        </a>
        <a href="#" onclick="switchDarkLight();" data-toggle="tooltip" data-original-title="Switch Dark/Light" data-placement="bottom" class="btn btn-default btn-sm navbar-btn navbar-right">
            <i class="glyphicon glyphicon-adjust"></i>
        </a>
        <#assign navbarItemList = sri.getThemeValues("STRT_HEADER_NAVBAR_ITEM")>
        <#list navbarItemList! as navbarItem>
            <#assign navbarItemTemplate = navbarItem?interpret>
            <@navbarItemTemplate/>
        </#list>
        <script>
            function switchDarkLight() {
                $("body").toggleClass("bg-dark dk");
                $("body").toggleClass("bg-light lter");
                var currentStyle = $("body").hasClass("bg-dark dk") ? "bg-dark dk" : "bg-light lter";
                $.ajax({ type:'POST', url:'/apps/setPreference', data:{ 'preferenceKey': 'OUTER_STYLE', 'preferenceValue': currentStyle }, dataType:'json' });
            }
        </script>
    </div>
  </div> <!-- container-fluid -->
</nav><!-- /.navbar -->

<#--
<header class="head">
    <div class="search-bar">
        <a data-original-title="Show/Hide Menu" data-placement="bottom" data-tooltip="tooltip" class="accordion-toggle btn btn-primary btn-sm visible-xs" data-toggle="collapse" href="#menu" id="menu-toggle">
            <i class="fa fa-expand"></i>
        </a>
        <form class="main-search">
            <div class="input-group">
                <input type="text" class="input-small form-control" placeholder="Live Search ...">
                <span class="input-group-btn">
                    <button class="btn btn-primary btn-sm text-muted" type="button"><i class="fa fa-search"></i></button>
                </span>
            </div>
        </form>
    </div>
    <div class="main-bar">
        <h3>${html_title!((sri.screenUrlInfo.targetScreen.getDefaultMenuName())!"Page")}</h3>
    </div>
</header>
-->
