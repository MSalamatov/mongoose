$(document).ready(function() {

	var VALUE_RUN_MODE_CLIENT = "VALUE_RUN_MODE_CLIENT";
	var VALUE_RUN_MODE_STANDALONE = "VALUE_RUN_MODE_STANDALONE";
	var VALUE_RUN_MODE_SERVER = "VALUE_RUN_MODE_SERVER";
	var VALUE_RUN_MODE_WSMOCK = "VALUE_RUN_MODE_WSMOCK";
	var runModes = [VALUE_RUN_MODE_CLIENT, VALUE_RUN_MODE_STANDALONE, VALUE_RUN_MODE_SERVER, VALUE_RUN_MODE_WSMOCK];
	var COUNT_OF_RECORDS = 50;

	initComponents();
	initWebSocket();
	if ($.cookie("websocket")) {
		server.connect();
	}

	$("#runmode").val($.cookie("runmode"));

	$(".add-node").click(function() {
		if ($(".data-node").is(":visible")) {
			$(".data-node").hide();
		} else {
			$(".data-node").show();
		}
	});

	$(".add-driver").click(function() {
		if ($(".driver").is(":visible")) {
			$(".driver").hide();
		} else {
			$(".driver").show();
		}
	});

	$(document).on("click", ".remove", function() {
		$(this).parent().parent().remove();
	});

	$(document).on("click", "#save", function() {
		$(".storages").append(appendBlock("dataNodes", $("#data-node-text").val()));
		$("#data-node-text").val("");
		$(".data-node").hide();
	});

	$(document).on("click", "#save-driver", function() {
		$(".drivers").append(appendBlock("drivers", $("#driver-text").val()));
		$("#driver-text").val("");
		$(".driver").hide();
	});

	$(document).on("click", "#standalone", function() {
		$("#runmode").val(VALUE_RUN_MODE_STANDALONE);
		$(this).addClass("active");
		$("#distributed").removeClass("active");
		$("#wsmock").removeClass("active");
		$("#driver").removeClass("active");
	});

	$(document).on("click", "#distributed", function() {
		$("#runmode").val(VALUE_RUN_MODE_CLIENT);
		$(this).addClass("active");
		$("#standalone").removeClass("active");
		$("#wsmock").removeClass("active");
		$("#driver").removeClass("active");
	});

	$(document).on("click", "#driver", function() {
		$("#runmode").val(VALUE_RUN_MODE_SERVER);
		$(this).addClass("active");
		$("#standalone").removeClass("active");
		$("#wsmock").removeClass("active");
		$("#distributed").removeClass("active");
	});

	$(document).on("click", "#wsmock", function() {
		$("#runmode").val(VALUE_RUN_MODE_WSMOCK);
		$(this).addClass("active");
		$("#standalone").removeClass("active");
		$("#distributed").removeClass("active");
		$("#driver").removeClass("active");
	});

	function initComponents() {
		$(".data-node").hide();
		$(".driver").hide();
	}

	function appendBlock(key, value) {
		html =
			'<div class="input-group">\
				<span class="input-group-addon">\
					<input type="checkbox" name=' + key +' value=' + value + '>\
				</span>\
				<label class="form-control">' +
					value +
				'</label>\
				<span class="input-group-btn">\
					<button type="button" class="btn btn-default remove">Remove</button>\
				</span>\
			</div>';
		return html;
	}

	function initWebSocket() {
		this.server = {
			connect: function() {
				var location = document.location.toString().replace('http://', 'ws://') + "logs";
				this._ws = new WebSocket(location);
				this._ws.onopen = this._onopen;
				this._ws.onmessage = this._onmessage;
				this._ws.onclose = this._onclose;
			},
			_onopen : function() {
				server._send('websockets!');
			},
			_send : function(message) {
				if (this._ws) {
					this._ws.send(message);
				}
			},
			send : function(text) {
				if (text != null && text.length > 0)
					server._send(text);
			},
			_onmessage : function(m) {
				runModes.forEach(function(entry) {
					var json = JSON.parse(m.data);
					// fix later
					if (!json.message.message) {
						str = json.message.messagePattern.split("{}");
						resultString = "";
						for (s = 0; s < str.length - 1; s++) {
							resultString += str[s]+json.message.stringArgs[s];
						}
						json.message.message = resultString + str[str.length - 1];
					}
					switch (json.marker.name) {
						case "err":
							if ($("#"+entry+"errors-log table tbody tr").length > COUNT_OF_RECORDS) {
								$("#"+entry+"errors-log table tbody tr:first-child").remove();
							}
							$("#"+entry+"errors-log table tbody").append(appendStringToTable(json));
							break;
						case "msg":
							if ($("#"+entry+"messages-csv table tbody tr").length > COUNT_OF_RECORDS) {
								$("#"+entry+"messages-csv table tbody tr:first-child").remove();
							}
							$("#"+entry+"messages-csv table tbody").append(appendStringToTable(json));
							break;
						case "perfSum":
							if ($("#"+entry+"perf-sum-csv table tbody tr").length > COUNT_OF_RECORDS) {
								$("#"+entry+"perf-sum-csv table tbody tr:first-child").remove();
							}
							$("#"+entry+"perf-sum-csv table tbody").append(appendStringToTable(json));
							break;
						case "perfAvg":
							if ($("#"+entry+"perf-avg-csv table tbody tr").length > COUNT_OF_RECORDS) {
								$("#"+entry+"perf-avg-csv table tbody tr:first-child").remove();
							}
							$("#"+entry+"perf-avg-csv table tbody").append(appendStringToTable(json));
							break;
					}
				});
			},
			_onclose : function(m) {
				this._ws = null;
			}
		};
	}

	function appendStringToTable(json) {
		html = '<tr>\
			<td class="filterable-cell">' + json.level.name + '</td>\
			<td class="filterable-cell">' + json.loggerName + '</td>\
			<td class="filterable-cell">' + json.marker.name + '</td>\
			<td class="filterable-cell">' + json.threadName + '</td>\
			<td class="filterable-cell">' + new Date(json.timeMillis) + '</td>\
			<td class="filterable-cell">' + json.message.message + '</td>\
			</tr>';
		return html;

	}

	$(document).on('submit', '#mainForm',  function(e) {
		e.preventDefault();
		$.post("/start", $("#mainForm").serialize(), function(data, status) {
			$.cookie("runmode", $("#runmode").val());
			location.reload();
			$.cookie("websocket", true);
		});
	});

	$(".clear").click(function() {
		$(this).parent().find("tbody tr").remove();
	});

});