var jupyterHeader = $("span.flex-spacer");
var jupyterSaveWidget = $("span.save_widget");
var cnvrLogo = $("<img/>", { 
	src: "http://engineering.conversantmedia.com/assets/images/engineering_logo.png",
	height: "28px",
	alt: "Conversant Media : Engineering" });

var cnvrSpan = $("<span/>", { 
	id: "cnvrSpan"
});
cnvrSpan.append(cnvrLogo);

jupyterHeader.append(cnvrLogo);
jupyterSaveWidget.append(cnvrSpan);
jupyterHeader.css("text-align", "center");
