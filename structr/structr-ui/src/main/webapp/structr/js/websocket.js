/*
 *  Copyright (C) 2012 Axel Morgner
 *
 *  This file is part of structr <http://structr.org>.
 *
 *  structr is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  structr is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with structr.  If not, see <http://www.gnu.org/licenses/>.
 */


var ws;
var token;
var loggedIn = false;
var user;

function connect() {

    if (token) {
	loggedIn = true;
    }

    try {

	var isEnc = (window.location.protocol == 'https:');
	var host = document.location.host;
	var wsUrl = 'ws' + (isEnc ? 's' : '') + '://' + host + wsRoot;

	if (debug) console.log(wsUrl);
	if ('WebSocket' in window) {
	    ws = new WebSocket(wsUrl, 'structr');
	}
	else if ('MozWebSocket' in window) {
	    ws = new MozWebSocket(wsUrl, 'structr');
	} else {
	    alert('Your browser doesn\'t support WebSocket.');
	    return;
	}

	log('State: ' + ws.readyState);
		
	var entityId;
	var parent, entity;

	ws.onmessage = function(message) {

	    var data = $.parseJSON(message.data);
	    if (debug) console.log(data);

	    //var msg = $.parseJSON(message);
	    var type = data.type;
	    var command = data.command;
            var parentId = data.id;
            var resourceId = data.data.resourceId;
	    var msg = data.message;
	    var result = data.result;
	    var sessionValid = data.sessionValid;
	    var code = data.code;
	    var callback = data.callback;

	    if (true) {
		if (debug) console.log('command: ' + command);
		if (debug) console.log('type: ' + type);
		if (debug) console.log('code: ' + code);
		if (debug) console.log('callback: ' + callback);
		if (debug) console.log('sessionValid: ' + sessionValid);
	    }
	    if (debug) console.log('result: ' + $.toJSON(result));

	    if (command == 'LOGIN') { /*********************** LOGIN ************************/
		token = data.token;
		user = data.data.username;
		if (debug) console.log('token', token);
		
		if (sessionValid) {
		    $.cookie('structrSessionToken', token);
		    $.cookie('structrUser', user);
		    $.unblockUI({
			fadeOut: 25
		    });
		    $('#logout_').html('Logout <span class="username">' + user + '</span>');

		    Structr.loadInitialModule();
					
		} else {
		    $.cookie('structrSessionToken', '');
		    $.cookie('structrUser', '');
		    clearMain();

		    Structr.login();
		}

	    } else if (command == 'LOGOUT') { /*********************** LOGOUT ************************/

		$.cookie('structrSessionToken', '');
		$.cookie('structrUser', '');
		clearMain();
		Structr.login();

	    } else if (command == 'STATUS') { /*********************** STATUS ************************/
		if (debug) console.log('Error code: ' + code);
				
		if (code == 403) {
		    Structr.login('Wrong username or password!');
		} else if (code == 401) {
		    Structr.login('Session invalid');
		} else {
		    var msgClass;
		    var codeStr = code.toString();
		    if (codeStr.startsWith('20')) {
			msgClass = 'success';
			$('#dialogBox .dialogMsg').html('<div class="infoBox ' + msgClass + '">' + msg + '</div>');
		    } else if (codeStr.startsWith('30')) {
			msgClass = 'info';
			$('#dialogBox .dialogMsg').html('<div class="infoBox ' + msgClass + '">' + msg + '</div>');
		    } else if (codeStr.startsWith('40')) {
			msgClass = 'warning';
			$('#dialogBox .dialogMsg').html('<div class="infoBox ' + msgClass + '">' + msg + '</div>');
		    } else {
			Structr.error("Error", true);
			msgClass = 'error';
			$('#errorBox .errorMsg').html('<div class="infoBox ' + msgClass + '">' + msg + '</div>');
		    }

		}

	    } else if (command == 'TREE') { /*********************** TREE ************************/
				
		if (debug) console.log('Render Tree');
		if (debug) console.log(data.root, data.id);
				
		_Entities.renderTree(data.root, data.id);

	    } else if (command == 'GET') { /*********************** GET ************************/

		if (debug) console.log('GET:', data);

		var d = data.data.displayElementId;
		if (debug) console.log('displayElementId', d);

		var parentElement;
		if (d != null) {
		    parentElement = $($(d)[0]);
		} else {
		    parentElement = $($('.' + data.id + '_')[0]);
		}

		if (debug) console.log('parentElement', parentElement);
		var key = data.data.key;
		var value = data.data[key];

		var attrElement = $(parentElement.find('.' + key + '_')[0]);
		if (debug) console.log('attrElement', attrElement);
		if (debug) console.log(key, value);

		if (attrElement && value) {

		    if (typeof value == 'boolean') {
			if (debug) console.log(attrElement, value);
			_Entities.changeBooleanAttribute(attrElement, value);

		    } else if (key == 'ownerId') {
			// append whole node

			var user = {};
			user.id = value;
			user.type = 'User';
			user.name = 'testuser';

			parentElement.append('<div class="user ' + user.id + '_">'
			    + '<img class="typeIcon" src="icon/user.png">'
			    + ' <b class="name_">' + user.name + '</b> <span class="id">' + user.id + '</span>'
			    + '</div>');
                        
		    } else {
			if (debug) console.log('appending ' + value + ' to attrElement', attrElement);
			attrElement.append(value);
			attrElement.val(value);
			attrElement.show();
		    }
		}

	    } else if (command == 'CHILDREN') { /*********************** CHILDREN ************************/

		
		if (debug) console.log('CHILDREN:', parentId, resourceId);

		$(result).each(function(i, child) {
		    if (debug) console.log('CHILDREN: ', child);
		    _Entities.appendObj(child, data.id, resourceId);
		});


	    } else if (command == 'LIST') { /*********************** LIST ************************/
				
		if (debug) console.log('LIST:', result);
                				
		$(result).each(function(i, entity) {
		    if (debug) console.log('LIST: ' + entity.type);
		    _Entities.appendObj(entity);
		});

	    } else if (command == 'DELETE') { /*********************** DELETE ************************/
		var elementSelector = '.' + data.id + '_';
		if (debug) console.log($(elementSelector));
		$(elementSelector).remove();
		if (buttonClicked) enable(buttonClicked);
		_Resources.reloadPreviews();

	    } else if (command == 'REMOVE') { /*********************** REMOVE ************************/

		if (debug) console.log(data);

		parentId = data.id;
		entityId = data.data.id;

		parent = $('.' + parentId + '_');
		entity = $('.' + entityId + '_', parent);

		if (debug) console.log(parent);
		if (debug) console.log(entity);

		//var id = getIdFromClassString(entity.attr('class'));
		//entity.id = id;
		if (entity.hasClass('user')) {
		    if (debug) console.log('remove user from group');
		    _UsersAndGroups.removeUserFromGroup(entityId, parentId);

		} else if (entity.hasClass('component')) {
		    if (debug) console.log('remove component from resource');
		    _Resources.removeComponentFromResource(entityId, parentId);
		    _Resources.reloadPreviews();

		} else if (entity.hasClass('element')) {
		    if (debug) console.log('remove element from resource');
		    _Resources.removeElementFromResource(entityId, parentId);
		    _Resources.reloadPreviews();

		} else if (entity.hasClass('content')) {
		    if (debug) console.log('remove content from element');
		    _Resources.removeContentFromElement(entityId, parentId);
		    _Resources.reloadPreviews();

		} else if (entity.hasClass('file')) {
		    if (debug) console.log('remove file from folder');
		    _Files.removeFileFromFolder(entityId, parentId);

		} else if (entity.hasClass('image')) {
		    if (debug) console.log('remove image from folder');
		    _Files.removeImageFromFolder(entityId, parentId);

		} else {
		//if (debug) console.log('remove element');
		//entity.remove();
		}

		_Resources.reloadPreviews();
		if (debug) console.log('Removed ' + entityId + ' from ' + parentId);

	    } else if (command == 'CREATE' || command == 'ADD' || command == 'IMPORT') { /*********************** CREATE, ADD, IMPORT ************************/
                
                //console.log(command, result, data, data.data);
				
		$(result).each(function(i, entity) {
                    _Entities.appendObj(entity, parentId, resourceId, command == 'ADD');
		});

		_Resources.reloadPreviews();

	    } else if (command == 'UPDATE') { /*********************** UPDATE ************************/
		var element = $( '.' + data.id + '_');
		var input = $('.props tr td.value input', element);
		if (debug) console.log(element);

		// remove save and cancel icons
		input.parent().children('.icon').each(function(i, img) {
		    $(img).remove();
		});

		// make inactive
		input.removeClass('active');
		if (debug) console.log(element);

		// update values with given key
		for (key in data.data) {
		    var attrElement = element.children('.' + key + '_');
		    var inputElement = element.children('.props tr td.' + key + ' input');
		    if (debug) console.log(attrElement, inputElement);
		    var newValue = data.data[key];

		    if (debug) console.log(key, newValue, typeof newValue);
		    if (typeof newValue  == 'boolean') {

			_Entities.changeBooleanAttribute(attrElement, newValue);
                        
		    } else {

			attrElement.animate({
			    color: '#81ce25'
			}, 100, function() {
			    $(this).animate({
				color: '#333333'
			    }, 200);
			});
                    
			attrElement.text(newValue);
			inputElement.val(newValue);

			// hook for CodeMirror edit areas
			if (editor && editor.id == data.id && key == 'content') {
			    if (debug) console.log(editor.id);
			    editor.setValue(newValue);
			    editor.setCursor(editorCursor);
			}
		    }

		}

		// refresh preview iframe
		input.data('changed', false);
	    //_Resources.reloadPreviews();
	    } else if (command == 'WRAP') { /*********************** WRAP ************************/

		if (debug) console.log('WRAP');

	    } else {
		if (debug) console.log('Received unknown command: ' + command);

		if (sessionValid == false) {
		    if (debug) console.log('invalid session');
		    $.cookie('structrSessionToken', '');
		    $.cookie('structrUser', '');
		    clearMain();

		    Structr.login();
		}
	    }
	}

	ws.onclose = function() {
	    //Structr.confirmation('Connection lost or timed out.<br>Reconnect?', Structr.init);
	    log('Connection was lost or timed out. Trying automatic reconnect');
	    Structr.reconnect();
	}

    } catch (exception) {
	log('Error in connect(): ' + exception);
	Structr.init();
    }

}

function sendObj(obj) {

    if (token) {
	obj.token = token;
    }

    text = $.toJSON(obj);

    if (!text) {
	log('No text to send!');
	return false;
    }

    try {
	ws.send(text);
	log('Sent: ' + text);
    } catch (exception) {
	log('Error in send(): ' + exception);
	return false;
    }
    return true;
}

function send(text) {

    log(ws.readyState);

    var obj = $.parseJSON(text);

    return sendObj(obj);
}

function log(msg) {
    if (debug) console.log(msg);
    $("#log").append("<br />" + msg);
}


function getAnchorFromUrl(url) {
    if (url) {
	var pos = url.lastIndexOf('#');
	if (pos > 0) {
	    return url.substring(pos+1, url.length);
	}
    }
    return null;
}


function utf8_to_b64( str ) {
    //return window.btoa(unescape(encodeURIComponent( str )));
    return window.btoa(str);
}

function b64_to_utf8( str ) {
    //return decodeURIComponent(escape(window.atob( str )));
    return window.atob(str);
}