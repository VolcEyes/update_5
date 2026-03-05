package com.example.galleryapp

class Image {
    var imagePath:String= ""
    var imageTitle:String= ""

    constructor(imagePath: String?, imageTitle: String?) {
        this.imagePath = imagePath!!
        this.imageTitle = imageTitle!!
    }
    constructor()
    {}
}