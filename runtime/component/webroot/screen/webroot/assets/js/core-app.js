
var CoreApp = function () {

    var isRTL = false;

    var handleAjaxForm = function() {
        if (jQuery.fn.ajaxForm) {
            $('.page-content form.ajax-form').each(function() {
                var dataContainerAttr = $(this).attr("background-reload-id");
                var dataMessageAttr = $(this).attr("background-message");
                var pageContentBody = $('.page-content .page-content-body');
                if (typeof dataContainerAttr !== 'undefined' && dataContainerAttr !== false) {
                    pageContentBody = $(dataContainerAttr);
                }
                var backgroundMessage = 'Submit form successfully';
                if (typeof dataMessageAttr !== 'undefined' && dataMessageAttr !== false) {
                    backgroundMessage = dataMessageAttr;
                }
                $(this).ajaxForm({
                    success: function(res, statusText, xhr, $form) {
                        CoreApp.alert({type: (res.sStatus == 'OK' ? 'success' : 'danger'),
                                    icon: (res.sStatus == 'OK' ? 'check' : 'warning'),
                                    message: (res.sStatus == 'OK' ? backgroundMessage : "Fail to submit form"),
                                    closeInSeconds: 5,
                                    place: 'prepend'});
                        CoreApp.stopPageLoading();
                        CoreApp.fixContentHeight(); // fix content height
                        CoreApp.initAjax(); // initialize core stuff
                    },
                    dataType: 'json',
                    resetForm: false
                });
            });

            $('.page-content button.ajax-submit').each(function () {
                var formAttr = $(this).attr("form");
                var backgroundMessage = 'Submit form successfully';
                var dataMessageAttr = $(this).attr("background-message");
                if (typeof dataMessageAttr !== 'undefined' && dataMessageAttr !== false) {
                    backgroundMessage = dataMessageAttr;
                }
                var itemForm = $(this).closest('form');
                if (itemForm === 'undefined' || itemForm === false) {
                    itemForm = $(this).closest('div.form-row');
                }
                if (itemForm === 'undefined' || itemForm === false) {
                    itemForm = $(this).closest('tr');
                }
                if (typeof formAttr !== 'undefined' && formAttr !== false) {
                    $('#' + formAttr).ajaxForm({
                        success: function (res, statusText, xhr, $form) {
                            CoreApp.alert({type: (res.sStatus == 'OK' ? 'success' : 'danger'),
                                icon: (res.sStatus == 'OK' ? 'check' : 'warning'),
                                message: (res.sStatus == 'OK' ? backgroundMessage : "Fail to submit form"),
                                closeInSeconds: 5,
                                place: 'prepend'});
                            if (res.sStatus === 'OK') {itemForm.remove();}
                            CoreApp.stopPageLoading();
                            CoreApp.fixContentHeight(); // fix content height
                            CoreApp.initAjax(); // initialize core stuff
                        },
                        dataType: 'json',
                        resetForm: false
                    });
                }
            });
        }
    }

    // Handles custom checkboxes & radios using jQuery Uniform plugin
    var handleUniform = function () {
        if (!jQuery().uniform) {
            return;
        }

        var test = $("input[type=checkbox]:not(.toggle, .make-switch), input[type=radio]:not(.toggle, .star, .make-switch)");
        if (test.size() > 0) {
            test.each(function () {
                if ($(this).parents(".checker").size() == 0) {
                    $(this).show();
                    $(this).uniform();
                }
            });
        }
    }

    var handleBootstrapSwitch = function () {
        if (!jQuery().bootstrapSwitch) {
            return;
        }
        $('input.make-switch').each(function() {
            if ($(this).parents('.has-switch').size() === 0) {
                $(this).bootstrapSwitch();
            }
        });
    }

    var handleAlerts = function () {
        $('body').on('click', '[data-close="alert"]', function(e){
            $(this).parent('.alert').hide();
            e.preventDefault();
        });
    }

    // Handle Select2 Dropdowns
    var handleSelect2 = function() {
        if (jQuery().select2) {
            $('select.select2me').select2({
                placeholder: "Select ...",
                allowClear: true,
                escapeMarkup: function (m) {
                    return m;
                }
            });
        }
    }

    var handleWysihtml5TextArea = function() {
        if (jQuery().wysihtml5) {
            $('textarea.wysihtml5').each(function() {
                if ($(this).next('input[type=hidden]').size() === 0) {
                    $(this).wysihtml5({"stylesheets": ["/assets/plugins/bootstrap-wysihtml5/wysiwyg-color.css"]});
                }
            });
        }
    }

    var handleDateTimePicker = function() {
        if (jQuery().datepicker) {
            $('div.datepicker').datepicker({
                rtl: CoreApp.isRTL(), autoclose: true,
                pickerPosition: (CoreApp.isRTL() ? "bottom-right" : "bottom-left")
            });
        }
        if (jQuery().datetimepicker) {
            $('div.datetimepicker').datetimepicker({
                autoclose: true,
                isRTL: CoreApp.isRTL(),
                pickerPosition: (CoreApp.isRTL() ? "bottom-right" : "bottom-left")
            });
        }
        if (jQuery().timepicker) {
            $('div.timepicker').timepicker({
                autoclose: true,
                minuteStep: 5,
                showSeconds: true,
                showMeridian: false
            });
        }
    }

    var handleRequiredControlLabel = function() {
        $('.control-label').each(function() {
            var label = $(this);
            if (label.attr("for") !== null) {
                if ($("#" + label.attr("for")).attr("required")) {
                    label.addClass("required");
                }
            }
        });
    }

    return {

        //main function to initiate the theme
        init: function () {
            //core handlers
            handleRequiredControlLabel();
            handleUniform(); // handle custom radio & checkboxes

            //ui component handlers
            handleSelect2(); // handle custom Select2 dropdowns
            handleWysihtml5TextArea();
            handleBootstrapSwitch(); // handle bootstrap switch plugin
            handleAlerts(); //handle closabled alerts
            handleDateTimePicker();
            handleAjaxForm();
        },

        //main function to initiate core javascript after ajax complete
        initAjax: function () {
            handleRequiredControlLabel();
            handleUniform(); // handle custom radio & checkboxes
            handleSelect2(); // handle custom Select2 dropdowns
            handleWysihtml5TextArea();
            handleBootstrapSwitch(); // handle bootstrap switch plugin
            handleDateTimePicker();
            handleAjaxForm();
        },

        // wrapper function to  block element(indicate loading)
        blockUI: function (options) {
            var options = $.extend(true, {}, options);
            var html = '';
            if (options.iconOnly) {
                html = '<div class="loading-message ' + (options.boxed ? 'loading-message-boxed' : '')+'"><img style="" src="/assets/img/loading-spinner-grey.gif" align=""></div>';
            } else if (options.textOnly) {
                html = '<div class="loading-message ' + (options.boxed ? 'loading-message-boxed' : '')+'"><span>&nbsp;&nbsp;' + (options.message ? options.message : 'LOADING...') + '</span></div>';
            } else {
                html = '<div class="loading-message ' + (options.boxed ? 'loading-message-boxed' : '')+'"><img style="" src="/assets/img/loading-spinner-grey.gif" align=""><span>&nbsp;&nbsp;' + (options.message ? options.message : 'LOADING...') + '</span></div>';
            }

            if (options.target) { // element blocking
                var el = jQuery(options.target);
                if (el.height() <= ($(window).height())) {
                    options.cenrerY = true;
                }
                el.block({
                    message: html,
                    baseZ: options.zIndex ? options.zIndex : 1000,
                    centerY: options.cenrerY != undefined ? options.cenrerY : false,
                    css: {
                        top: '10%',
                        border: '0',
                        padding: '0',
                        backgroundColor: 'none'
                    },
                    overlayCSS: {
                        backgroundColor: options.overlayColor ? options.overlayColor : '#000',
                        opacity: options.boxed ? 0.05 : 0.1,
                        cursor: 'wait'
                    }
                });
            } else { // page blocking
                $.blockUI({
                    message: html,
                    baseZ: options.zIndex ? options.zIndex : 1000,
                    css: {
                        border: '0',
                        padding: '0',
                        backgroundColor: 'none'
                    },
                    overlayCSS: {
                        backgroundColor: options.overlayColor ? options.overlayColor : '#000',
                        opacity: options.boxed ? 0.05 : 0.1,
                        cursor: 'wait'
                    }
                });
            }
        },

        // wrapper function to  un-block element(finish loading)
        unblockUI: function (target) {
            if (target) {
                jQuery(target).unblock({
                    onUnblock: function () {
                        jQuery(target).css('position', '');
                        jQuery(target).css('zoom', '');
                    }
                });
            } else {
                $.unblockUI();
            }
        },

        startPageLoading: function(message) {
            $('.page-loading').remove();
            $('body').append('<div class="page-loading"><img src="/assets/img/loading-spinner-grey.gif"/>&nbsp;&nbsp;<span>' + (message ? message : 'Loading...') + '</span></div>');
        },

        stopPageLoading: function() {
            $('.page-loading').remove();
        },

        getUniqueID: function (prefix) {
            return 'prefix_' + Math.floor(Math.random() * (new Date()).getTime());
        },

        initUniform: function() {
            handleUniform();
        },

        //check RTL mode
        isRTL: function () {
            return isRTL;
        },


        alert: function(options) {

            options = $.extend(true, {
                container: "", // alerts parent container(by default placed after the page breadcrumbs)
                place: "append", // append or prepent in container
                type: 'success',  // alert's type
                message: "",  // alert's message
                close: true, // make alert closable
                reset: true, // close all previouse alerts first
                focus: true, // auto scroll to the alert after shown
                closeInSeconds: 0, // auto close after defined seconds
                icon: "" // put icon before the message
            }, options);

            var id = CoreApp.getUniqueID("app_alert");

            var html = '<div id="'+id+'" class="app-alerts alert alert-'+options.type+' fade in">' + (options.close ? '<button type="button" class="close" data-dismiss="alert" aria-hidden="true"></button>' : '' ) + (options.icon != "" ? '<i class="fa-lg fa fa-'+options.icon + '"></i>  ' : '') + options.message+'</div>'

            if (options.reset) {0
                $('.app-alerts').remove();
            }

            if (!options.container) {
                $('.page-breadcrumb').after(html);
            } else {
                if (options.place == "append") {
                    $(options.container).append(html);
                } else {
                    $(options.container).prepend(html);
                }
            }

            if (options.focus) {
                CoreApp.scrollTo($('#' + id));
            }

            if (options.closeInSeconds > 0) {
                setTimeout(function(){
                    $('#' + id).remove();
                }, options.closeInSeconds * 1000);
            }
        },

        select2Formatter: function() {
            return {
                defaultFormatter: function(option) {
                    if (!option.id) return option.text; // optgroup
                    return option.text;
                }
            }
        }

    };

}();