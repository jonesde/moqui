
<#-- <div class="text-center"><img src="assets/img/logo.png" alt="Metis Logo"></div> -->
<div class="tab-content">
    <div id="login" class="tab-pane active">
        <form method="post" action="${sri.makeUrlByType("login", "transition", null, "false").getUrl()}" class="form-signin">
            <p class="text-muted text-center">Enter your username and password to sign in</p>
            <#-- not needed for this request: <input type="hidden" name="moquiSessionToken" value="${ec.web.sessionToken}"> -->
            <input type="text" name="username" value="${(ec.getWeb().getErrorParameters().get("username"))!""}" placeholder="Username" required="required" class="form-control top">
            <#if !ec.getWeb()?? || ec.getWeb().getSession().getAttribute("moqui.tenantAllowOverride")! != "N">
                <input type="password" name="password" placeholder="Password" required="required" class="form-control middle">
                <input type="text" name="tenantId" placeholder="Tenant ID" class="form-control bottom">
            <#else>
                <input type="password" name="password" placeholder="Password" required="required" class="form-control bottom">
            </#if>
            <button class="btn btn-lg btn-primary btn-block" type="submit">Sign in</button>
        </form>
    </div>
    <div id="reset" class="tab-pane">
        <form method="post" action="${sri.makeUrlByType("resetPassword", "transition", null, "false").getUrl()}" class="form-signin">
            <p class="text-muted text-center">Enter your username to reset and email your password</p>
            <input type="hidden" name="moquiSessionToken" value="${ec.web.sessionToken}">
            <#if !ec.getWeb()?? || ec.getWeb().getSession().getAttribute("moqui.tenantAllowOverride")! != "N">
                <input type="text" name="username" value="${(ec.getWeb().getErrorParameters().get("username"))!""}" placeholder="Username" required="required" class="form-control top">
                <input type="text" name="tenantId" placeholder="Tenant ID" class="form-control bottom">
            <#else>
                <input type="text" name="username" placeholder="Username" required="required" class="form-control">
            </#if>
            <button class="btn btn-lg btn-danger btn-block" type="submit">Reset and Email Password</button>
        </form>
    </div>
    <div id="change" class="tab-pane">
        <form method="post" action="${sri.makeUrlByType("changePassword", "transition", null, "false").getUrl()}" class="form-signin">
            <p class="text-muted text-center">Enter details to change your password</p>
            <input type="hidden" name="moquiSessionToken" value="${ec.web.sessionToken}">
            <input type="text" name="username" value="${(ec.getWeb().getErrorParameters().get("username"))!""}" placeholder="Username" required="required" class="form-control top">
            <input type="password" name="oldPassword" placeholder="Old Password" required="required" class="form-control middle">
            <input type="password" name="newPassword" placeholder="New Password" required="required" class="form-control middle">
            <#if !ec.getWeb()?? || ec.getWeb().getSession().getAttribute("moqui.tenantAllowOverride")! != "N">
                <input type="password" name="newPasswordVerify" placeholder="New Password Verify" required="required" class="form-control middle">
                <input type="text" name="tenantId" placeholder="Tenant ID" class="form-control bottom">
            <#else>
                <input type="password" name="newPasswordVerify" placeholder="New Password Verify" required="required" class="form-control bottom">
            </#if>
            <button class="btn btn-lg btn-danger btn-block" type="submit">Change Password</button>
        </form>
    </div>
</div>
<div class="text-center">
    <ul class="list-inline">
        <li><a class="text-muted" href="#login" data-toggle="tab">Login</a></li>
        <li><a class="text-muted" href="#reset" data-toggle="tab">Reset Password</a></li>
        <li><a class="text-muted" href="#change" data-toggle="tab">Change Password</a></li>
    </ul>
</div>

<script>
  $(document).ready(function() {
    $('.list-inline li > a').click(function() {
        var activeForm = $(this).attr('href') + ' > form';
        $(activeForm).addClass('animated zoomIn');
        //set timer to 1 seconds, after that, unload the magic animation
        setTimeout(function() {
            $(activeForm).removeClass('animated zoomIn');
        }, 1000);
    });
  });
</script>
