define([
	'jquery',
	'./websockets/webSocketController',
	'./tab/scenariosController',
	'./tab/defaultsController',
	'./tab/tests/testsController',
	'../common/openFileHandler',
	'text!../../templates/navbar.hbs',
	'text!../../templates/base.hbs',
	'text!../../templates/tab/properties/buttons.hbs',
	'text!../../templates/tab/properties/configurations.hbs',
	'../common/util/handlebarsUtil',
	'../common/util/templatesUtil',
	'../common/util/cssUtil',
	'../common/util/tabsUtil',
	'../common/constants'
], function ($,
             webSocketController,
             scenariosController,
             defaultsController,
             testsController,
             openFileHandler,
             navbarTemplate,
             baseTemplate,
             buttonsTemplate,
             configurationsTemplate,
             hbUtil,
             templatesUtil,
             cssUtil,
             tabsUtil,
             constants) {

	const MODE = templatesUtil.modes();
	const EXTENDED_MODE = templatesUtil.objPartToArray(MODE, 2);
	const TAB_TYPE = templatesUtil.tabTypes();
	const BLOCK = templatesUtil.blocks();
	const plainId = templatesUtil.composeId;
	const jqId = templatesUtil.composeJqId;

	var currentMode = MODE.STANDALONE;
	var currentTabType = TAB_TYPE.SCENARIOS;

	// render html and bind events of basic elements and same for all tabs elements 
	function render(scenariosArray, configObject) {
		const renderer = rendererFactory();
		renderer.navbar(version(configObject));
		renderer.base();
		renderer.buttons();
		renderer.configurations();
		scenariosController.render(scenariosArray);
		defaultsController.render(configObject);
		testsController.render();
		makeModeActive(currentMode);
		makeTabActive(currentTabType);
		renderer.start();
	}

	function version(configObject) {
		var version = configObject.run.version;
		if (version) {
			version = version.charAt(0).toUpperCase() + version.slice(1);
		} else {
			version = 'Unknown';
		}
		return version;
	}

	function tabJqId(tabType) {
		return jqId([tabType, 'tab']);
	}

	function makeTabActive(tabType) {
		tabsUtil.showTabAsActive('tab', tabType);
		tabsUtil.showActiveTabDependentElements('tab-dependent', tabType);
		cssUtil.hide(jqId(['start']), jqId(['properties', 'block']), jqId(['tests', 'block']));
		switch (tabType) {
			case TAB_TYPE.SCENARIOS:
				scenariosController.setTabParameters();
				cssUtil.show(jqId(['start']), jqId(['properties', 'block']));
				break;
			case TAB_TYPE.DEFAULTS:
				defaultsController.setTabParameters();
				cssUtil.show(jqId(['start']), jqId(['properties', 'block']));
				break;
			case TAB_TYPE.TESTS:
				cssUtil.show(jqId(['tests', 'block']));
				break;
		}
		currentTabType = tabType;
	}

	function makeModeActive(mode) {
		const TAB_CLASS = templatesUtil.tabClasses();
		$(jqId(['mode', currentMode])).removeClass(TAB_CLASS.ACTIVE);
		$(jqId(['mode', mode])).addClass(TAB_CLASS.ACTIVE);
		const modeTabElem = $(jqId(['mode', 'main']));
		modeTabElem.text('Mode: ' + mode);
		currentMode = mode;
		defaultsController.setRunMode(currentMode);
		$('#run\\.mode').find('input').val(currentMode);
		for (var i = 0; i < EXTENDED_MODE.length; i++) {
			if (mode === EXTENDED_MODE[i]) {
				cssUtil.processClassElements('mode-dependent',
					function (elemSelector) {
						elemSelector.show();
					});
				return;
			}
		}
		if (currentTabType === TAB_TYPE.SCENARIOS) {
			makeTabActive(TAB_TYPE.DEFAULTS);
		}
		cssUtil.processClassElements('mode-dependent',
			function (elemSelector) {
				elemSelector.hide();
			});
	}

	const rendererFactory = function () {
		const binder = clickEventBinderFactory();
		const CONFIG_TABS = templatesUtil.objPartToArray(TAB_TYPE, 2);

		function renderNavbar(runVersion) {
			hbUtil.compileAndInsertInsideBefore('body', navbarTemplate, {
				version: runVersion,
				modes: MODE
			});
			binder.mode();
			binder.tab();
		}

		function renderBase() {
			hbUtil.compileAndInsertInside('#app', baseTemplate);
			const configElem = $('#all-buttons');
			$.each(CONFIG_TABS, function (index, value) {
				if (index === 0) {
					const detailsTree = $('<ul/>', {
						id: plainId([BLOCK.TREE, value, 'details']),
						class: BLOCK.TREE + ' ' + 'tab-dependent'
					});
					configElem.after(detailsTree);
					detailsTree.hide();
				}
				configElem.after(
					$('<ul/>', {
						id: plainId([BLOCK.TREE, value]),
						class: BLOCK.TREE + ' ' + 'tab-dependent'
					}));
			})
		}

		function renderButtons() {
			// object to array
			const BUTTON_TYPE = templatesUtil.commonButtonTypes();
			const BUTTONS = templatesUtil.objToArray(BUTTON_TYPE);
			$.each(CONFIG_TABS, function (index, value) {
				hbUtil.compileAndInsertInsideBefore(jqId(['all', BLOCK.BUTTONS]), buttonsTemplate,
					{'buttons': BUTTONS, 'tab-type': value});
			});
			binder.tabButtons(BUTTON_TYPE, CONFIG_TABS);
		}

		function renderConfigurations() {
			$.each(CONFIG_TABS, function (index, value) {
				hbUtil.compileAndInsertInsideBefore(jqId(['all', BLOCK.CONFIG]), configurationsTemplate,
					{'tab-type': value});
			});
		}

		function renderStart() {
			binder.startButton();
		}

		return {
			navbar: renderNavbar,
			base: renderBase,
			buttons: renderButtons,
			configurations: renderConfigurations,
			start: renderStart
		}
	};

	const clickEventBinderFactory = function () {
		function bindModeButtonClickEvent(mode) {
			const modeId = jqId(['mode', mode]);
			$(modeId).click(function () {
				makeModeActive(mode);
			})
		}

		function bindTabClickEvents() {
			tabsUtil.bindTabClickEvents(TAB_TYPE, tabJqId, makeTabActive);
		}

		function bindModeButtonClickEvents() {
			$.each(MODE, function (key, value) {
				bindModeButtonClickEvent(value);
			});
		}

		function buttonJqId(buttonType, tabName) {
			return jqId([buttonType, tabName]);
		}

		function fillTheField(tabName, BUTTON_TYPE) {
			const openInputFileId = buttonJqId(BUTTON_TYPE.OPEN_INPUT_FILE, tabName);
			const openInputTextId = buttonJqId(BUTTON_TYPE.OPEN_INPUT_TEXT, tabName);
			const openFileName = $(openInputFileId).val();
			if (openFileName) {
				$(openInputTextId).val(openFileName)
			} else {
				$(openInputTextId).val('No ' + tabName.slice(0, -1) + ' chosen')
			}
		}

		function passClick(tabName, BUTTON_TYPE) {
			const openInputFileElem = $(buttonJqId(BUTTON_TYPE.OPEN_INPUT_FILE, tabName));
			$(buttonJqId(BUTTON_TYPE.OPEN, tabName)).click(function () {
				openInputFileElem.trigger('click');
			});
			openInputFileElem.change(function (data) {
				fillTheField(tabName, BUTTON_TYPE);
				openFileHandler.event(data);
			})
		}

		function bindTabButtonsClickEvents(BUTTON_TYPE, CONFIG_TABS) {
			$.each(CONFIG_TABS, function (index, value) {
				passClick(value, BUTTON_TYPE);
				bindSaveAsButtonClickEvent(value, BUTTON_TYPE);
			});
		}

		function startButtonClickEvent(startJson) {
			var isConfirmed = true;
			if (defaultsController.isChanged() || scenariosController.isChanged()) {
				isConfirmed = confirm('If properties were changed Mongoose' +
					' will save it' +
					' automatically. ' +
					'Would you like to continue?');
			}
			if (isConfirmed) {
				$.ajax({
					type: 'PUT',
					url: '/run',
					dataType: 'json',
					contentType: constants.JSON_CONTENT_TYPE,
					data: JSON.stringify(startJson),
					processData: false
				}).done(function (testsArr) {
					testsController.updateTestsList(testsArr);
					console.log('Mongoose ran');
				});
			}
		}

		function bindStartButtonEvent() {
			$(jqId(['start'])).click(function () {
				const runConfig = defaultsController.getChangedAppConfig();
				const startJson = {};
				startJson['config'] = {
					config: runConfig // the server handles a configuration in this format
				};
				if (EXTENDED_MODE.indexOf(currentMode) > -1) {
					const runScenario = scenariosController.getChangedScenario();
					if (runScenario === null) {
						alert('Please, choose a scenario');
						return
					} else {
						startJson['scenario'] = runScenario;
					}
				}
				startButtonClickEvent(startJson);
			})
		}

		function bindSaveAsButtonClickEvent(tabName, BUTTON_TYPE) {
			saveFileAElem = $(jqId([BUTTON_TYPE.SAVE_AS, tabName]));
			saveFileAElem.click(function () {
				if ($(this).attr('href') === undefined) {
					alert('No ' + tabName + ' chosen')
				}
			});
		}

		return {
			mode: bindModeButtonClickEvents,
			tab: bindTabClickEvents,
			tabButtons: bindTabButtonsClickEvents,
			startButton: bindStartButtonEvent
		}
	};

	return {
		render: render
	};
});